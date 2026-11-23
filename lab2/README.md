# Lab 2 – Coffee shop server (Java, plain TCP sockets)

Uses only `java.net.ServerSocket` / `java.net.Socket` (socket, bind, listen, accept,
connect, send, receive, close). No higher-level networking libraries.

## Build
Requires a JDK (8 or newer).

    javac *.java

(If your `java` runtime is older than your `javac`, build with `javac --release 8 *.java`.)

## Run
Server (port is a parameter):

    java Server 5555

Client:

    java Client <host> <port>            e.g.  java Client 127.0.0.1 5555

Provider (same program, other role):

    java Client <host> <port> provider

## Protocol (one text line per message, ending with `\n`, max 256 bytes)
| Request | Answer(s) |
|---|---|
| `MENU` | `MENU coffee (4s) in stock: N; tea (3s) in stock: N; croissant (2s) in stock: N;` |
| `ORDER <product> <qty> [<product> <qty> ...]` | `ACCEPTED order=<id> time=<s>s`, later `READY order=<id>`; or `REFUSED not enough stock: ...`; or `ERR ...` |
| `PROVIDER` | `OK you are the provider` (then any pending `ALERT`s) |
| `DELIVER <product> <qty> [...]` (provider only) | `OK delivered, stock {...}` or `ERR ...` |
| `QUIT` | `BYE`, then the server closes the connection |
| `PING` (heartbeat, sent by the client every second) | `PONG` |

The server sends `ALERT <product> is low: N left` to the provider on its own
when a product falls below 5.

## Lost network and typing
- **Heartbeat.** The client sends `PING` every second and the server answers `PONG`.
  The client's receive has a 5 s timeout: if nothing arrives for 5 s the network
  is lost, and the client prints `Server unreachable ...` and ends. After a client's
  first `PING`, the server also reads with a 5 s timeout, so it notices a client
  whose network is gone and cancels its orders. (A lost network sends no FIN or
  RST, so without this a receive would just wait.) Clients that never send `PING`
  are still found by TCP keep-alive, only much later.
- **Typing.** The client puts the console in raw mode (Windows: a short PowerShell
  helper calls `SetConsoleMode`; Linux/macOS: `stty -icanon -echo`), reads key by
  key and echoes the keys itself (`Terminal.java`). A server message clears the
  input line, is printed, and the prompt and the typed text are printed again, so
  messages never get mixed into what you are typing. If the input is not a
  console (redirected), it uses plain line mode.
