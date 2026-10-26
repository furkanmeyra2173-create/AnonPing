#!/usr/bin/env python3
"""Patch the pinned MIT-licensed hev-socks5-tunnel with native IP packet loss.

Runs in GitHub Actions after the pinned upstream revision is fetched.
It fails if upstream changes expected anchors, preventing a silent build
without packet-level loss.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: patch_hev_packet_loss.py PATH_TO_HEV_SOCKS5_TUNNEL")

root = Path(sys.argv[1])

def replace_once(relative_path: str, old: str, new: str) -> None:
    path = root / relative_path
    source = path.read_text(encoding="utf-8")
    if new in source:
        return
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            f"{relative_path}: expected one patch anchor, found {count}: {old[:80]!r}"
        )
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

replace_once(
    "src/hev-socks5-tunnel.h",
    "void hev_socks5_tunnel_fini (void);\n",
    "void hev_socks5_tunnel_fini (void);\n"
    "void hev_socks5_tunnel_set_packet_loss (int loss_percent);\n",
)
replace_once(
    "src/hev-socks5-tunnel.c",
    "#include <sys/ioctl.h>\n",
    "#include <sys/ioctl.h>\n#include <stdlib.h>\n",
)
replace_once(
    "src/hev-socks5-tunnel.c",
    "static struct udp_pcb *udp;\n",
    """static struct udp_pcb *udp;

static atomic_int packet_loss_percent;

void
hev_socks5_tunnel_set_packet_loss (int loss_percent)
{
    if (loss_percent < 0)
        loss_percent = 0;
    if (loss_percent > 100)
        loss_percent = 100;

    atomic_store_explicit (&packet_loss_percent, loss_percent,
                           memory_order_release);
}

static int
packet_should_drop (void)
{
    int loss = atomic_load_explicit (&packet_loss_percent,
                                     memory_order_acquire);

    if (loss <= 0)
        return 0;
    if (loss >= 100)
        return 1;

    return arc4random_uniform (100) < (unsigned int)loss;
}
""",
)
replace_once(
    "src/hev-socks5-tunnel.c",
    """        if ((data[0] >> 4) == 4 ? (data[9] == IP_PROTO_UDP) :
                                  (data[6] == IP_PROTO_UDP))
            i++;

        hev_task_mutex_lock (&mutex);""",
    """        if ((data[0] >> 4) == 4 ? (data[9] == IP_PROTO_UDP) :
                                  (data[6] == IP_PROTO_UDP))
            i++;

        if (packet_should_drop ()) {
            pbuf_free (buf);
            if (i >= TUN_READ_BATCH) {
                hev_task_yield (HEV_TASK_YIELD);
                i = 0;
            }
            continue;
        }

        hev_task_mutex_lock (&mutex);""",
)
replace_once(
    "src/hev-socks5-tunnel.c",
    "    s = hev_tunnel_write (tun_fd, p);\n",
    "    if (packet_should_drop ())\n"
    "        return ERR_OK;\n\n"
    "    s = hev_tunnel_write (tun_fd, p);\n",
)
replace_once(
    "src/hev-jni.c",
    '#include "hev-main.h"\n',
    '#include "hev-main.h"\n#include "hev-socks5-tunnel.h"\n',
)
replace_once(
    "src/hev-jni.c",
    "static jboolean native_is_running (JNIEnv *env, jobject thiz);\n"
    "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n",
    "static jboolean native_is_running (JNIEnv *env, jobject thiz);\n"
    "static void native_set_packet_loss (JNIEnv *env, jobject thiz, jint loss_percent);\n"
    "static jlongArray native_get_stats (JNIEnv *env, jobject thiz);\n",
)
replace_once(
    "src/hev-jni.c",
    '    { "TProxyIsRunning", "()Z", (void *)native_is_running },\n'
    '    { "TProxyGetStats", "()[J", (void *)native_get_stats },',
    '    { "TProxyIsRunning", "()Z", (void *)native_is_running },\n'
    '    { "TProxySetPacketLoss", "(I)V", (void *)native_set_packet_loss },\n'
    '    { "TProxyGetStats", "()[J", (void *)native_get_stats },',
)
replace_once(
    "src/hev-jni.c",
    "static jlongArray\nnative_get_stats (JNIEnv *env, jobject thiz)\n{",
    "static void\nnative_set_packet_loss (JNIEnv *env, jobject thiz, jint loss_percent)\n{\n"
    "    hev_socks5_tunnel_set_packet_loss (loss_percent);\n"
    "}\n\n"
    "static jlongArray\nnative_get_stats (JNIEnv *env, jobject thiz)\n{",
)
print("Applied native TUN packet-loss patch successfully.")
