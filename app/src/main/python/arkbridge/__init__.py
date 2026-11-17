"""ArkBridge —— 把 ArkLink 的 OneBot 11 服务端，中继给只会当反向 WS 服务端的框架。

一句话：AstrBot 这类框架只肯「接」不肯「连」，ArkLink 只肯「接」不肯「连」，
ArkBridge 站在中间，两头都主动连出去，事件与 API 双向搬运。
"""

__version__ = "0.1.0"
__all__ = ["__version__"]
