//! Encrypted DNS (DoH / DoT) for names resolved by the engine's SOCKS layer.
//!
//! Selected through the environment:
//!   SOILDTUNNEL_DNS_MODE = plain | doh | dot
//!   SOILDTUNNEL_DOH_URL  = https://host[/path]      (DoH endpoint)
//!   SOILDTUNNEL_DOT_HOST = host[:port] | ip[:port]  (DoT endpoint, default port 853)
//!
//! Every failure falls back to the plain UDP path in socks.rs, so a blocked
//! encrypted endpoint can never take the tunnel's name resolution down.

use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

const DEFAULT_DOH_URL: &str = "https://cloudflare-dns.com/dns-query";
const DEFAULT_DOT_HOST: &str = "1.1.1.1";
const DEFAULT_DOT_PORT: u16 = 853;
const QTYPE_A: u16 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Plain,
    DoH,
    Dot,
}

pub fn mode() -> Mode {
    match std::env::var("SOILDTUNNEL_DNS_MODE")
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase()
        .as_str()
    {
        "doh" => Mode::DoH,
        "dot" => Mode::Dot,
        _ => Mode::Plain,
    }
}

/// Resolve `name` through the configured encrypted channel.
pub async fn resolve(name: &str) -> Result<IpAddr, String> {
    match mode() {
        Mode::DoH => doh_resolve(name).await,
        Mode::Dot => dot_resolve(name).await,
        Mode::Plain => Err("encrypted dns disabled".into()),
    }
}

// ---- DoH (RFC 8484) -------------------------------------------------------

async fn doh_resolve(name: &str) -> Result<IpAddr, String> {
    let url = std::env::var("SOILDTUNNEL_DOH_URL")
        .unwrap_or_default()
        .trim()
        .to_string();
    let url: String = if url.is_empty() {
        DEFAULT_DOH_URL.to_string()
    } else if url.starts_with("https://") && url.len() <= 200 {
        url
    } else {
        return Err("invalid SOILDTUNNEL_DOH_URL".into());
    };

    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(5))
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|e| format!("doh client: {e}"))?;

    let (query, id) = crate::socks::build_dns_query(name, QTYPE_A);
    let resp = client
        .post(&url)
        .header("Content-Type", "application/dns-message")
        .header("Accept", "application/dns-message")
        .body(query)
        .send()
        .await
        .map_err(|e| format!("doh post: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("doh status {}", resp.status()));
    }
    let body = resp
        .bytes()
        .await
        .map_err(|e| format!("doh read: {e}"))?;
    if !crate::socks::dns_response_matches(&body, id, name, QTYPE_A) {
        return Err("doh reply mismatch".into());
    }
    crate::socks::parse_dns_a(&body).ok_or_else(|| format!("no A record for {name}"))
}

// ---- DoT (RFC 7858) -------------------------------------------------------

async fn dot_resolve(name: &str) -> Result<IpAddr, String> {
    let host = std::env::var("SOILDTUNNEL_DOT_HOST")
        .unwrap_or_default()
        .trim()
        .trim_start_matches("tls://")
        .to_string();
    let host = if host.is_empty() || host.len() > 200 {
        DEFAULT_DOT_HOST.to_string()
    } else {
        host
    };
    let addr = dot_target_addr(&host).await?;

    let tcp = tokio::time::timeout(Duration::from_secs(5), tokio::net::TcpStream::connect(addr))
        .await
        .map_err(|_| "dot connect timeout".to_string())?
        .map_err(|e| format!("dot connect: {e}"))?;

    let server_name = dot_server_name(&host);
    let mut tls = tokio::time::timeout(
        Duration::from_secs(6),
        tokio_boring::connect(boring::ssl::SslConnector::builder(boring::ssl::SslMethod::tls())
            .map_err(|e| format!("dot tls builder: {e}"))?
            .build(),
        server_name.as_str(), tcp),
    )
    .await
    .map_err(|_| "dot handshake timeout".to_string())?
    .map_err(|e| format!("dot handshake: {e}"))?;

    let (query, id) = crate::socks::build_dns_query(name, QTYPE_A);
    let len = (query.len() as u16).to_be_bytes();
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    tls.write_all(&len).await.map_err(|e| format!("dot write: {e}"))?;
    tls.write_all(&query).await.map_err(|e| format!("dot write: {e}"))?;
    tls.flush().await.map_err(|e| format!("dot flush: {e}"))?;

    let mut len_buf = [0u8; 2];
    tls.read_exact(&mut len_buf).await.map_err(|e| format!("dot read: {e}"))?;
    let msg_len = u16::from_be_bytes(len_buf) as usize;
    if msg_len == 0 || msg_len > 4096 {
        return Err(format!("dot bad length {msg_len}"));
    }
    let mut body = vec![0u8; msg_len];
    tls.read_exact(&mut body).await.map_err(|e| format!("dot read: {e}"))?;

    if !crate::socks::dns_response_matches(&body, id, name, QTYPE_A) {
        return Err("dot reply mismatch".into());
    }
    crate::socks::parse_dns_a(&body).ok_or_else(|| format!("no A record for {name}"))
}

/// `host` or `host:port` -> SocketAddr; a hostname is bootstrapped via the system resolver.
async fn dot_target_addr(host: &str) -> Result<SocketAddr, String> {
    let (name, port) = match host.rsplit_once(':') {
        Some((h, p)) if p.chars().all(|c| c.is_ascii_digit()) && !p.is_empty() => {
            (h, p.parse::<u16>().map_err(|_| "dot bad port")?)
        }
        _ => (host, DEFAULT_DOT_PORT),
    };

    if let Ok(ip) = name.parse::<IpAddr>() {
        return Ok(SocketAddr::new(ip, port));
    }
    let targets = tokio::net::lookup_host((name, port))
        .await
        .map_err(|e| format!("dot bootstrap resolve: {e}"))?;
    targets.next().ok_or_else(|| format!("dot no address for {name}"))
}

/// SNI for the DoT handshake: strip any trailing :port and brackets.
fn dot_server_name(host: &str) -> String {
    let without_port = match host.rsplit_once(':') {
        Some((h, p)) if p.chars().all(|c| c.is_ascii_digit()) && !p.is_empty() => h,
        _ => host,
    };
    without_port
        .trim_matches(|c| c == '[' || c == ']')
        .to_string()
}
