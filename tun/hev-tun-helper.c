/*
 * hev-tun-helper - privileged companion for SoildTunnel Linux.
 *
 * Runs as root via pkexec. Opens /dev/net/tun, brings up soildtunnel0,
 * adds default routes through it and pumps packets between the TUN and
 * the engine's local SOCKS5 port using hev-socks5-tunnel.
 *
 * Usage:
 *   hev-tun-helper --mtu 1500 [--ipv6] [--socks 127.0.0.1:1819]
 */
#define _GNU_SOURCE
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/if.h>
#include <linux/if_tun.h>
#include <arpa/inet.h>

extern int hev_socks5_tunnel_main(const char *config_path, int tun_fd);
extern void hev_socks5_tunnel_quit(void);

static volatile sig_atomic_t g_stop = 0;
static int g_tun_fd = -1;
static char g_iface[IFNAMSIZ] = "soildtunnel0";
static int g_ipv6 = 0;

static void on_signal(int sig)
{
	(void)sig;
	g_stop = 1;
	hev_socks5_tunnel_quit();
}

static int run_cmd(const char *fmt, ...)
{
	char cmd[512];
	va_list ap;
	int rc;

	va_start(ap, fmt);
	vsnprintf(cmd, sizeof(cmd), fmt, ap);
	va_end(ap);
	rc = system(cmd);
	if (rc != 0)
		fprintf(stderr, "cmd failed (%d): %s\n", rc, cmd);
	return rc;
}

static int tun_open(void)
{
	struct ifreq ifr;
	int fd, err;

	fd = open("/dev/net/tun", O_RDWR);
	if (fd < 0) {
		perror("open /dev/net/tun");
		return -1;
	}
	memset(&ifr, 0, sizeof(ifr));
	ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
	strncpy(ifr.ifr_name, g_iface, IFNAMSIZ - 1);
	err = ioctl(fd, TUNSETIFF, &ifr);
	if (err < 0) {
		perror("ioctl TUNSETIFF");
		close(fd);
		return -1;
	}
	return fd;
}

static void cleanup_routes(void)
{
	run_cmd("ip route del default dev %s metric 50 2>/dev/null", g_iface);
	if (g_ipv6)
		run_cmd("ip -6 route del default dev %s metric 50 2>/dev/null", g_iface);
	run_cmd("ip link del %s 2>/dev/null", g_iface);
}

int main(int argc, char **argv)
{
	int mtu = 1500;
	const char *socks = "127.0.0.1:1819";
	char cfg_path[256];
	FILE *cfg;

	for (int i = 1; i < argc; i++) {
		if (!strcmp(argv[i], "--mtu") && i + 1 < argc) {
			mtu = atoi(argv[++i]);
		} else if (!strcmp(argv[i], "--ipv6")) {
			g_ipv6 = 1;
		} else if (!strcmp(argv[i], "--socks") && i + 1 < argc) {
			socks = argv[++i];
		} else if (!strcmp(argv[i], "--iface") && i + 1 < argc) {
			snprintf(g_iface, sizeof(g_iface), "%s", argv[++i]);
		}
	}

	signal(SIGINT, on_signal);
	signal(SIGTERM, on_signal);

	g_tun_fd = tun_open();
	if (g_tun_fd < 0)
		return 1;

	char socks_host[64] = "127.0.0.1";
	int socks_port = 1819;
	{
		const char *colon = strrchr(socks, ':');
		if (colon) {
			size_t hl = (size_t)(colon - socks);
			if (hl < sizeof(socks_host)) {
				memcpy(socks_host, socks, hl);
				socks_host[hl] = '\0';
			}
			socks_port = atoi(colon + 1);
		}
	}

	snprintf(cfg_path, sizeof(cfg_path), "/tmp/soildtunnel-hev-%d.yaml", (int)getpid());
	cfg = fopen(cfg_path, "w");
	if (!cfg) {
		perror("fopen config");
		close(g_tun_fd);
		return 1;
	}
	fprintf(cfg,
		"tunnel:\n"
		"  mtu: %d\n"
		"  ipv4: 10.10.14.1\n"
		"  ipv6: 'fc00::10:10:14:1'\n"
		"socks5:\n"
		"  address: %s\n"
		"  port: %d\n"
		"  udp: 'udp'\n"
		"misc:\n"
		"  tcp-read-write-timeout: 300000\n"
		"  udp-read-write-timeout: 120000\n",
		mtu, socks_host, socks_port);
	fclose(cfg);

	if (run_cmd("ip addr add 10.10.14.1/30 dev %s 2>/dev/null", g_iface) != 0)
		run_cmd("ip addr replace 10.10.14.1/30 dev %s", g_iface);
	if (g_ipv6)
		run_cmd("ip -6 addr replace fc00::10:10:14:1/126 dev %s", g_iface);
	run_cmd("ip link set mtu %d dev %s", mtu, g_iface);
	run_cmd("ip link set up dev %s", g_iface);
	run_cmd("ip route add default dev %s metric 50 2>/dev/null", g_iface);
	if (g_ipv6)
		run_cmd("ip -6 route add default dev %s metric 50 2>/dev/null", g_iface);

	int rc = hev_socks5_tunnel_main(cfg_path, g_tun_fd);

	cleanup_routes();
	unlink(cfg_path);
	close(g_tun_fd);
	return rc;
}
