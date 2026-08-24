package com.soildtunnel.app.core

import com.soildtunnel.app.model.RoutingMode
import com.soildtunnel.app.model.RoutingRule
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class MatchType {
    DOMAIN,
    DOMAIN_SUFFIX,
    KEYWORD,
    REGEX,
    IPV4,
    IPV6,
    CIDR,
    DNS_CACHE,
    SNI,
    HTTP_HOST,
    DEFAULT
}

data class RoutingDecision(
    val mode: RoutingMode,
    val matchedRule: RoutingRule?,
    val matchedBy: MatchType,
    val resolvedDomain: String?
)

/**
 * Rule-based flow classifier, merged into SoildTunnel. In SoildTunnel the domain/IP
 * rules the user types in Settings are enforced inside the Rust engine
 * (`--route-block` / `--route-direct`); this classifier is used by the
 * userspace tunnel bridge, which is only activated for per-app blocking.
 */
class RoutingEngine(rules: List<RoutingRule>) {
    private data class CompiledRule(
        val source: RoutingRule,
        val normalizedPattern: String,
        val regex: Regex?,
        val priority: Int
    )

    private val compiledRules = rules.mapNotNull(::compileRule)
        .sortedWith(compareByDescending<CompiledRule> { it.priority }.thenByDescending { it.normalizedPattern.length })
    private val decisionCache = ConcurrentHashMap<String, RoutingDecision>()
    private val containsDomainRules = compiledRules.any { !it.normalizedPattern.startsWith("ip:") }

    fun hasDomainRules(): Boolean = containsDomainRules

    fun resolve(
        destinationIp: String,
        destinationPort: Int,
        resolvedDomain: String?,
        tlsSni: String?,
        httpHost: String?
    ): RoutingDecision {
        val normalizedDomain = normalizeDomain(resolvedDomain)
        val normalizedSni = normalizeDomain(tlsSni)
        val normalizedHost = normalizeDomain(httpHost)
        val key = "$destinationIp:$destinationPort:$normalizedDomain:$normalizedSni:$normalizedHost"
        return decisionCache[key] ?: resolveInternal(
            destinationIp,
            normalizedDomain,
            normalizedSni,
            normalizedHost
        ).also { decisionCache[key] = it }
    }

    private fun resolveInternal(
        destinationIp: String,
        resolvedDomain: String?,
        tlsSni: String?,
        httpHost: String?
    ): RoutingDecision {
        matchIpRules(destinationIp)?.let { return it }
        resolvedDomain?.let { matchDomainRules(it, MatchType.DNS_CACHE)?.let { decision -> return decision } }
        tlsSni?.let { matchDomainRules(it, MatchType.SNI)?.let { decision -> return decision } }
        httpHost?.let { matchDomainRules(it, MatchType.HTTP_HOST)?.let { decision -> return decision } }
        return RoutingDecision(RoutingMode.TUNNEL, null, MatchType.DEFAULT, resolvedDomain ?: tlsSni ?: httpHost)
    }

    private fun matchIpRules(ip: String): RoutingDecision? {
        val address = parseNumericAddress(ip) ?: return null
        for (rule in compiledRules) {
            if (!rule.normalizedPattern.startsWith("ip:")) continue
            val value = rule.normalizedPattern.removePrefix("ip:")
            if (matchesIp(address, value)) {
                val type = when {
                    value.contains('/') -> MatchType.CIDR
                    address.address.size == 16 -> MatchType.IPV6
                    else -> MatchType.IPV4
                }
                return RoutingDecision(rule.source.mode, rule.source, type, null)
            }
        }
        return null
    }

    private fun matchDomainRules(domain: String, sourceType: MatchType): RoutingDecision? {
        for (rule in compiledRules) {
            val pattern = rule.normalizedPattern
            if (pattern.startsWith("ip:")) continue
            val matched = when {
                pattern.startsWith("keyword:") -> domain.contains(pattern.removePrefix("keyword:"))
                pattern.startsWith("regexp:") -> rule.regex?.containsMatchIn(domain) == true
                pattern.startsWith("domain:") -> matchesDomain(domain, pattern.removePrefix("domain:"))
                else -> matchesDomain(domain, pattern)
            }
            if (!matched) continue
            val type = when {
                pattern.startsWith("keyword:") -> MatchType.KEYWORD
                pattern.startsWith("regexp:") -> MatchType.REGEX
                domain == pattern.removePrefix("domain:") -> MatchType.DOMAIN
                else -> if (sourceType == MatchType.DNS_CACHE || sourceType == MatchType.SNI || sourceType == MatchType.HTTP_HOST) sourceType else MatchType.DOMAIN_SUFFIX
            }
            return RoutingDecision(rule.source.mode, rule.source, type, domain)
        }
        return null
    }

    private fun compileRule(rule: RoutingRule): CompiledRule? {
        val normalized = rule.pattern.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null
        val regex = if (normalized.startsWith("regexp:")) {
            runCatching { Regex(normalized.removePrefix("regexp:")) }.getOrNull() ?: return null
        } else {
            null
        }
        val priority = when {
            normalized.startsWith("ip:") && !normalized.contains('/') -> 600
            normalized.startsWith("ip:") -> 500
            normalized.startsWith("domain:") -> 400
            normalized.startsWith("keyword:") -> 200
            normalized.startsWith("regexp:") -> 100
            else -> 300
        }
        return CompiledRule(rule, normalized, regex, priority)
    }

    private fun matchesDomain(domain: String, pattern: String): Boolean {
        val normalized = pattern.removePrefix("*.").trimEnd('.')
        return domain == normalized || domain.endsWith(".$normalized")
    }

    private fun matchesIp(address: InetAddress, pattern: String): Boolean {
        val parts = pattern.split('/', limit = 2)
        val network = parseNumericAddress(parts[0]) ?: return false
        if (network.address.size != address.address.size) return false
        if (parts.size == 1) return network.address.contentEquals(address.address)
        val prefix = parts[1].toIntOrNull() ?: return false
        val maxPrefix = network.address.size * 8
        if (prefix !in 0..maxPrefix) return false
        val wholeBytes = prefix / 8
        val remainingBits = prefix % 8
        for (index in 0 until wholeBytes) {
            if (network.address[index] != address.address[index]) return false
        }
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (network.address[wholeBytes].toInt() and mask) == (address.address[wholeBytes].toInt() and mask)
    }

    private fun parseNumericAddress(value: String): InetAddress? {
        val candidate = value.trim().substringBefore('%')
        val isIpv4 = candidate.matches(Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$"))
        val isIpv6 = candidate.contains(':') && candidate.matches(Regex("^[0-9a-fA-F:.]+$"))
        if (!isIpv4 && !isIpv6) return null
        return runCatching { InetAddress.getByName(candidate) }.getOrNull()
    }

    private fun normalizeDomain(value: String?): String? {
        return value?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }

    fun clearCache() {
        decisionCache.clear()
    }
}
