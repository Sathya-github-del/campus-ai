"""In-memory sliding-window rate limiter (per process). Swap for Redis when running >1 worker."""
from __future__ import annotations

import math
import threading
import time
from collections import defaultdict, deque


class RateLimiter:
    def __init__(self, limit: int, window_seconds: float, clock=time.monotonic):
        self.limit = limit
        self.window = window_seconds
        self.clock = clock
        self.hits: dict[str, deque[float]] = defaultdict(deque)
        self.lock = threading.Lock()

    def check(self, key: str) -> int:
        """Record a hit. Returns 0 if allowed, else seconds until the next hit would be allowed."""
        if self.limit <= 0:
            return 0
        now = self.clock()
        with self.lock:
            q = self.hits[key]
            while q and q[0] <= now - self.window:
                q.popleft()
            if len(q) >= self.limit:
                return max(1, math.ceil(q[0] + self.window - now))
            q.append(now)
            if len(self.hits) > 100_000:  # bound memory: forget idle keys
                for k in [k for k, v in self.hits.items() if not v or v[-1] <= now - self.window]:
                    del self.hits[k]
            return 0
