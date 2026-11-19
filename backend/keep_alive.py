import time
import threading
import torch

_keep_alive_running = False

def _anti_disconnect_worker(interval_seconds: int = 45):
    global _keep_alive_running
    _keep_alive_running = True
    print(f"🛡️ Colab Keep-Alive active (ping interval: {interval_seconds}s)")
    
    while _keep_alive_running:
        try:
            # Touch CUDA lightly if available
            if torch.cuda.is_available():
                x = torch.zeros(1, device="cuda")
                del x
        except Exception:
            pass
        time.sleep(interval_seconds)

def start_keep_alive(interval_seconds: int = 45):
    """Start anti-disconnect background daemon thread"""
    t = threading.Thread(target=_anti_disconnect_worker, args=(interval_seconds,), daemon=True)
    t.start()
    return t

def stop_keep_alive():
    global _keep_alive_running
    _keep_alive_running = False

