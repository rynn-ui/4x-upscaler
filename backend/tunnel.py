import base64
import io
import os
import platform
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.request
from typing import Optional


def is_port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def find_free_port(start_port: int = 8000) -> int:
    port = start_port
    while is_port_in_use(port):
        port += 1
    return port


def _drain(proc) -> None:
    try:
        for _ in proc.stdout:
            pass
    except Exception:
        pass


def _read_url_proc(proc, patterns, timeout: float = 60.0) -> Optional[str]:
    start = time.time()
    compiled = [re.compile(p) for p in patterns]
    while time.time() - start < timeout:
        line = proc.stdout.readline()
        if not line and proc.poll() is not None:
            break
        if line:
            for c in compiled:
                m = c.search(line)
                if m:
                    return m.group(0)
    return None


def start_cloudflared_tunnel(port: int = 8000) -> Optional[str]:
    """Download & start Cloudflare Tunnel (cloudflared). Returns the public HTTPS URL."""
    system = platform.system().lower()

    ext = ".exe" if "windows" in system else ""
    bin_path = os.path.join(tempfile.gettempdir(), f"cloudflared{ext}")

    if "linux" in system:
        cf_url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64"
    elif "windows" in system:
        cf_url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe"
    else:
        cf_url = "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-darwin-amd64"

    try:
        if not os.path.exists(bin_path):
            tmp_bin = bin_path + ".tmp"
            # If a background download is in progress, wait for it to finish atomic move
            t_wait = 0
            while not os.path.exists(bin_path) and os.path.exists(tmp_bin) and t_wait < 30:
                time.sleep(0.5)
                t_wait += 1

            if not os.path.exists(bin_path):
                print(f"📥 Downloading cloudflared binary to {bin_path}...")
                urllib.request.urlretrieve(cf_url, tmp_bin)
                if "windows" not in system:
                    os.chmod(tmp_bin, 0o755)
                os.replace(tmp_bin, bin_path)

        cmd = [bin_path, "tunnel", "--no-autoupdate", "--url", f"http://127.0.0.1:{port}"]
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        url = _read_url_proc(proc, [r"https://[a-zA-Z0-9-]+\.trycloudflare\.com"], timeout=75)
        if url:
            threading.Thread(target=_drain, args=(proc,), daemon=True).start()
            tunnel_url = url.strip()
            # Warm up Cloudflare edge routing to ensure /api/v1/health is live before returning
            for _ in range(8):
                try:
                    req = urllib.request.Request(
                        f"{tunnel_url}/api/v1/health",
                        headers={"User-Agent": "UpscalerWarmup/1.0"}
                    )
                    with urllib.request.urlopen(req, timeout=1.5) as resp:
                        if resp.status == 200:
                            break
                except Exception:
                    time.sleep(0.4)
            return tunnel_url
        proc.kill()
    except Exception as e:
        print(f"⚠️ Cloudflare tunnel error: {e}")

    return None


def start_ngrok_tunnel(port: int = 8000, authtoken: Optional[str] = None) -> Optional[str]:
    """Start high-stability Ngrok tunnel (no 100MB body limit, persistent edge connection)."""
    token = (authtoken or os.environ.get("NGROK_AUTHTOKEN") or "").strip()
    if not token:
        return None

    try:
        try:
            import pyngrok.ngrok as ngrok
        except ImportError:
            print("📥 Installing pyngrok for stable high-performance tunnel...")
            subprocess.run([sys.executable, "-m", "pip", "install", "-q", "pyngrok"], check=True)
            import pyngrok.ngrok as ngrok

        ngrok.set_auth_token(token)
        try:
            ngrok.disconnect()
        except Exception:
            pass

        tunnel = ngrok.connect(port, "http")
        url = tunnel.public_url
        if url:
            if url.startswith("http://"):
                url = "https://" + url[7:]
            print(f"✅ Connected to Ngrok tunnel: {url}")
            return url.strip()
    except Exception as e:
        print(f"⚠️ Ngrok tunnel failed ({e}), falling back to Cloudflare...")

    return None


def start_best_tunnel(port: int = 8000, ngrok_token: Optional[str] = None) -> Optional[str]:
    """Attempts Ngrok first if a token is configured; otherwise uses Cloudflare tunnel."""
    token = (ngrok_token or os.environ.get("NGROK_AUTHTOKEN") or "").strip()
    if token:
        url = start_ngrok_tunnel(port, authtoken=token)
        if url:
            return url
    return start_cloudflared_tunnel(port)


def build_pairing_deep_link(public_url: str, auth_token: Optional[str] = None) -> str:
    params = {"url": public_url.strip()}
    if auth_token:
        params["token"] = auth_token.strip()
    return "upscaler://connect?" + urllib.parse.urlencode(params)


def _render_qr_base64(payload: str) -> Optional[str]:
    try:
        import qrcode

        qr = qrcode.QRCode(
            version=None,
            error_correction=qrcode.constants.ERROR_CORRECT_M,
            box_size=6,
            border=2,
        )
        qr.add_data(payload)
        qr.make(fit=True)
        img = qr.make_image(fill_color="#12141F", back_color="#FFFFFF")
        buf = io.BytesIO()
        img.save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode("ascii")
    except Exception:
        return None


def display_connection_card(public_url: str, auth_token: Optional[str] = None) -> None:
    """Render 1-Click Android deep-link button + compact QR code with session auth token in Colab."""
    if auth_token is None:
        try:
            from server import get_auth_token

            auth_token = get_auth_token()
        except Exception:
            auth_token = None

    deep_link = build_pairing_deep_link(public_url, auth_token)
    qr_b64 = _render_qr_base64(deep_link)

    print("\n" + "=" * 56)
    print("⚡ UPSCALER STUDIO BACKEND IS ONLINE!")
    print(f"🔗 Tunnel URL: {public_url}")
    print("🔐 Session authentication enabled (embedded in 1-tap button & QR).")
    print("=" * 56 + "\n")

    try:
        from IPython.display import HTML, display

        qr_block = ""
        if qr_b64:
            qr_block = f"""
            <div style="margin-top: 18px; padding-top: 16px; border-top: 1px solid #1E2235;">
                <div style="font-size: 12px; color: #A0A7C0; margin-bottom: 10px;">
                    Or scan from Android Studio app (<b>Scan QR</b>):
                </div>
                <img src="data:image/png;base64,{qr_b64}"
                     alt="Upscaler Studio Pairing QR Code"
                     style="width: 156px; height: 156px; border-radius: 12px; padding: 8px; background: #ffffff;" />
            </div>
            """

        html_code = f"""
        <div style="
            background: #090A10;
            border: 1px solid #1E2235;
            border-radius: 20px;
            padding: 24px 28px;
            max-width: 480px;
            margin: 20px auto;
            color: #ffffff;
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            box-shadow: 0 12px 36px rgba(0,0,0,0.6);
            text-align: center;
        ">
            <div style="display: inline-flex; align-items: center; gap: 8px; margin-bottom: 12px;">
                <span style="width: 10px; height: 10px; border-radius: 50%; background: #10B981; box-shadow: 0 0 12px #10B981;"></span>
                <span style="font-size: 13px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.08em; color: #10B981;">
                    Backend Live & Secured
                </span>
            </div>

            <h2 style="margin: 0 0 8px 0; font-size: 22px; font-weight: 800; color: #ffffff;">
                ⚡ Upscaler Studio
            </h2>
            <p style="margin: 0 0 20px 0; font-size: 13px; color: #9BA2B8;">
                Tap below on your phone or scan the QR code to pair automatically.
            </p>

            <a href="{deep_link}" target="_blank" onclick="window.location.href='{deep_link}';" style="
                display: block;
                width: 100%;
                box-sizing: border-box;
                background: linear-gradient(90deg, #6C5CE7 0%, #8B5CF6 100%);
                color: #ffffff;
                text-decoration: none;
                font-weight: 700;
                font-size: 16px;
                padding: 16px 24px;
                border-radius: 14px;
                box-shadow: 0 6px 20px rgba(108, 92, 231, 0.4);
            ">
                🚀 OPEN IN UPSCALER APP
            </a>

            {qr_block}

            <div style="
                margin-top: 14px;
                font-size: 11px;
                color: #8A90A5;
                word-break: break-all;
            ">
                {public_url}
            </div>
        </div>
        """
        display(HTML(html_code))
    except Exception:
        pass
