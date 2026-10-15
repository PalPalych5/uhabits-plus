import ctypes
from ctypes import wintypes
import json
import threading
import time

class MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [
        ('dwLength', wintypes.DWORD),
        ('dwMemoryLoad', wintypes.DWORD),
        ('ullTotalPhys', ctypes.c_uint64),
        ('ullAvailPhys', ctypes.c_uint64),
        ('ullTotalPageFile', ctypes.c_uint64),
        ('ullAvailPageFile', ctypes.c_uint64),
        ('ullTotalVirtual', ctypes.c_uint64),
        ('ullAvailVirtual', ctypes.c_uint64),
        ('ullAvailExtendedVirtual', ctypes.c_uint64),
    ]

class FILETIME(ctypes.Structure):
    _fields_ = [('dwLowDateTime', wintypes.DWORD), ('dwHighDateTime', wintypes.DWORD)]

def filetime_to_int(ft):
    return (ft.dwHighDateTime << 32) | ft.dwLowDateTime

class HostMonitor:
    def __init__(self, interval_seconds=1.0):
        self.interval = interval_seconds
        self.samples = []
        self._stop_event = threading.Event()
        self._thread = None
        self._prev_idle = None
        self._prev_kernel = None
        self._prev_user = None

    def _sample(self):
        mem = MEMORYSTATUSEX()
        mem.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(mem))

        idle_ft = FILETIME()
        kernel_ft = FILETIME()
        user_ft = FILETIME()
        ctypes.windll.kernel32.GetSystemTimes(ctypes.byref(idle_ft), ctypes.byref(kernel_ft), ctypes.byref(user_ft))

        idle = filetime_to_int(idle_ft)
        kernel = filetime_to_int(kernel_ft)
        user = filetime_to_int(user_ft)

        cpu_pct = None
        if self._prev_idle is not None:
            d_idle = idle - self._prev_idle
            d_kernel = kernel - self._prev_kernel
            d_user = user - self._prev_user
            d_total = d_kernel + d_user
            if d_total > 0:
                cpu_pct = max(0.0, min(100.0, (1.0 - (d_idle / d_total)) * 100.0))

        self._prev_idle = idle
        self._prev_kernel = kernel
        self._prev_user = user

        return {
            'timestamp': time.time(),
            'monotonicNanos': time.monotonic_ns(),
            'cpuPercent': round(cpu_pct, 2) if cpu_pct is not None else None,
            'memoryLoadPercent': mem.dwMemoryLoad,
            'availPhysMb': round(mem.ullAvailPhys / (1024 * 1024), 1),
            'totalPhysMb': round(mem.ullTotalPhys / (1024 * 1024), 1)
        }

    def _run(self):
        # Initial sample to set baselines
        self._sample()
        while not self._stop_event.is_set():
            time.sleep(self.interval)
            if self._stop_event.is_set():
                break
            self.samples.append(self._sample())

    def start(self):
        self._stop_event.clear()
        self.samples = []
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def stop(self):
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=3.0)

    def summary(self):
        cpus = [s['cpuPercent'] for s in self.samples if s.get('cpuPercent') is not None]
        mems = [s['memoryLoadPercent'] for s in self.samples]
        avails = [s['availPhysMb'] for s in self.samples]
        return {
            'sampleCount': len(self.samples),
            'intervalSeconds': self.interval,
            'cpuPercent': {
                'mean': round(sum(cpus) / len(cpus), 2) if cpus else None,
                'min': min(cpus) if cpus else None,
                'max': max(cpus) if cpus else None
            },
            'memoryLoadPercent': {
                'mean': round(sum(mems) / len(mems), 2) if mems else None,
                'max': max(mems) if mems else None
            },
            'minAvailPhysMb': min(avails) if avails else None,
            'samples': self.samples
        }

if __name__ == '__main__':
    mon = HostMonitor(interval_seconds=0.5)
    print("Starting 2s test monitor...")
    mon.start()
    time.sleep(2.0)
    mon.stop()
    s = mon.summary()
    print(f"Captured {s['sampleCount']} samples. CPU mean: {s['cpuPercent']['mean']}%, memLoad: {s['memoryLoadPercent']['mean']}%")
