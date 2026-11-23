import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;

public class Server 
{
    static final Map<String, Integer> PREP = new LinkedHashMap<>();

    static 
    {
        PREP.put("coffee", 4);
        PREP.put("tea", 3);
        PREP.put("croissant", 2);
    }

    static final int MAX_LINE = 256, LOW = 5, MAX_QTY = 1_000_000, MAX_STOCK = 10_000_000;
    static final int SILENCE_LIMIT_MS = 5000;   // a client that sends PING must not be silent longer
    static final Object LOCK = new Object();
    static final Map<String, Integer> stock = new LinkedHashMap<>();  
    static Conn providerConn;                                         
    static int orderSeq;                                              
    static final BlockingQueue<Order> queue = new LinkedBlockingQueue<>(); 
    static final String POISON = new String("<end>");

    enum State 
    { 
        QUEUED, PREPARING, DONE, CANCELLED 
    }

    static final class Order 
    {
        final int id; final Conn conn; final Map<String, Integer> items; final int secs;
        State state = State.QUEUED;                                   
        final CountDownLatch cancelled = new CountDownLatch(1);

        Order(int id, Conn conn, Map<String, Integer> items, int secs) 
        {
            this.id = id; this.conn = conn; this.items = items; this.secs = secs;
        }
    }

    static final class Conn 
    {
        final Socket sock; final String name;
        final BlockingQueue<String> out = new ArrayBlockingQueue<>(500);
        final Set<Order> orders = new HashSet<>();                  
        boolean provider;                                              
        boolean heartbeat;                    // it sends PING, so silence means it is gone
        volatile boolean graceful;

        Conn(Socket s) 
        { 
            sock = s; name = s.getRemoteSocketAddress().toString(); 
        }

        void send(String m) 
        {
            if (!out.offer(m)) 
                { 
                    log("ERROR " + name + ": client too slow, dropping it"); 
                    close(); 
                }
        }

        void closeAfterFlush() 
        { 
            graceful = true; 
            if (!out.offer(POISON)) 
                close(); 
        }

        void close() 
        {
            try 
            { 
                sock.close(); 
            } 
            catch (IOException ignored) 
            {

            }
            out.offer(POISON);
        }
    }

    static synchronized void log(String m) 
    {
        System.out.println(LocalTime.now().withNano(0) + " " + m);
    }

    public static void main(String[] args) throws Exception 
    {
        if (args.length != 1) 
        { 
            System.err.println("Usage: java Server <port>"); 
            return; 
        }
        int port;
        try
        {
            port = Integer.parseInt(args[0]);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        }
        catch (NumberFormatException e)
        {
            System.err.println("Bad port: " + args[0] + " (must be 1..65535)");
            return;
        }
        Random rnd = new Random();
        for (String p : PREP.keySet()) 
            stock.put(p, 10 + rnd.nextInt(41));  
        int workers = Runtime.getRuntime().availableProcessors();
        for (int i = 1; i <= workers; i++) 
        {
            final int n = i;
            new Thread(() -> worker(n), "worker-" + n).start();
        }
        ServerSocket ss = new ServerSocket(port);
        log("Server listening on port " + port + ", " + workers + " workers, stock " + stock);
        while (true) 
        {
            try 
            {
                Socket s = ss.accept();
                new Thread(() -> serve(s)).start();
            } 
            catch (Throwable e) 
            {
                log("ERROR accept: " + e);
                if (ss.isClosed()) return;
            }
        }
    }

    static void worker(int n) 
    {
        String w = "worker-" + n;
        while (true) 
        {
            Order o;
            try 
            { 
                o = queue.take(); 
            } 
            catch (InterruptedException e) 
            { 
                return; 
            }
            try 
            {
                synchronized (LOCK) 
                {
                    if (o.state != State.QUEUED) 
                        continue;    
                    o.state = State.PREPARING;
                }
                log(w + " preparing order #" + o.id + " of " + o.conn.name + " (" + o.secs + " s)");
                boolean cancelled = o.cancelled.await(o.secs, TimeUnit.SECONDS);
                if (cancelled) 
                { 
                    log(w + " stopped order #" + o.id + " (cancelled)"); 
                    continue; 
                }
                synchronized (LOCK) 
                {
                    if (o.state != State.PREPARING) 
                        continue;
                    o.state = State.DONE;
                    o.conn.orders.remove(o);
                }
                o.conn.send("READY order=" + o.id);
                log("order #" + o.id + " READY for " + o.conn.name + " (" + w + ")");
            } 
            catch (InterruptedException e) 
            {
                return;
            } 
            catch (Throwable t) 
            {
                log("ERROR " + w + ": " + t);
            }
        }
    }

    static void cancelLocked(Order o, String why) 
    {
        if (o.state != State.QUEUED && o.state != State.PREPARING) 
            return;
        boolean wasPreparing = o.state == State.PREPARING;
        o.state = State.CANCELLED;
        for (Map.Entry<String, Integer> e : o.items.entrySet()) 
            stock.merge(e.getKey(), e.getValue(), Integer::sum);
        queue.remove(o);
        o.cancelled.countDown();
        o.conn.orders.remove(o);
        log("order #" + o.id + " of " + o.conn.name + " cancelled (" + why + (wasPreparing ? ", was being prepared" : ", was waiting")
                + "), products back in stock");
    }

    static void serve(Socket s) 
    {
        Conn c;
        try 
        {
            s.setKeepAlive(true);
            s.setTcpNoDelay(true);
            c = new Conn(s);
        } 
        catch (Throwable t) 
        {
            log("ERROR new connection: " + t);
            try 
            { 
                s.close(); 
            } 
            catch (IOException ignored) 
            { 

            }
            return;
        }
        log("CONNECTED " + c.name);
        final Conn conn = c;
        Thread writer = new Thread(() -> 
        {
            try 
            {
                OutputStream os = s.getOutputStream();
                while (true) 
                {
                    String m = conn.out.take();
                    if (m == POISON) break;
                    os.write((m + "\n").getBytes(StandardCharsets.UTF_8));
                }
            } 
            catch (Exception e) 
            {
                log("ERROR write to " + conn.name + ": " + e);
            } 
            finally 
            {
                try 
                { 
                    s.close(); 
                } 
                catch (IOException ignored) 
                {

                }
            }
        });
        writer.start();
        try {
            LineReader reader = new LineReader(s.getInputStream(), MAX_LINE);
            while (true) 
            {
                String line = reader.readLine();
                if (line == null) 
                { 
                    log("DISCONNECTED " + c.name + " (closed the connection)"); 
                    break; 
                }
                if (line == LineReader.TOO_LONG) 
                {
                    log("ERROR " + c.name + ": message too long");
                    c.send("ERR message too long (max " + MAX_LINE + " bytes)");
                    continue;
                }
                try 
                {
                    handle(c, line);
                } 
                catch (Throwable t) 
                {
                    log("ERROR " + c.name + ": " + t);
                    c.send("ERR internal error");
                }
                if (c.graceful) 
                { 
                    log("DISCONNECTED " + c.name + " (QUIT)"); 
                    break; 
                }
            }
        } 
        catch (SocketTimeoutException e)
        {
            log("DISCONNECTED " + c.name + " (no PING for " + SILENCE_LIMIT_MS / 1000 + " s, network lost)");
        }
        catch (IOException e) 
        {
            log("DISCONNECTED " + c.name + " (connection lost: " + e.getMessage() + ")");
        } 
        catch (Throwable t) 
        {
            log("ERROR " + c.name + ": " + t);
        } 
        finally 
        {
            synchronized (LOCK) 
            {
                for (Order o : new ArrayList<>(c.orders)) 
                    cancelLocked(o, "client gone");
                if (providerConn == c) 
                { 
                    providerConn = null; 
                    log("provider " + c.name + " is gone, shop goes on"); 
                }
            }
            if (!c.graceful) 
                c.close();
        }
    }

    static final class BadInput extends Exception 
    {
        BadInput(String m) 
        { 
            super(m); 
        }
    }

    static void handle(Conn c, String line) 
    {
        String[] t = line.trim().split("\\s+");
        String cmd = t[0].toUpperCase(Locale.ROOT);
        switch (cmd) 
        {
            case "PING": ping(c);
            break;
            case "MENU": menu(c); 
            break;
            case "ORDER": order(c, t); 
            break;
            case "PROVIDER": becomeProvider(c); 
            break;
            case "DELIVER": deliver(c, t); 
            break;
            case "QUIT": c.send("BYE"); 
            c.closeAfterFlush(); 
            break;
            default:
                log("ERROR " + c.name + ": unknown command '" + printable(t[0]) + "'");
                c.send("ERR unknown command. Commands: MENU, ORDER <product> <qty> ..., QUIT"
                        + " (provider: PROVIDER, DELIVER <product> <qty> ...)");
        }
    }

    /**
     * Heartbeat: the client sends PING every second and expects PONG.
     * After the first PING the read gets a timeout, so a client whose network
     * is gone (no FIN, no RST ever arrives) is noticed within a few seconds.
     * Clients that never send PING are still found by TCP keep-alive.
     */
    static void ping(Conn c)
    {
        if (!c.heartbeat)
        {
            try
            {
                c.sock.setSoTimeout(SILENCE_LIMIT_MS);
                c.heartbeat = true;
            }
            catch (SocketException e)
            {
                log("ERROR " + c.name + ": cannot set read timeout: " + e.getMessage());
            }
        }
        c.send("PONG");
    }

    /** Keeps random bytes out of the log: only printable ASCII, at most 32 chars. */
    static String printable(String s)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && i < 32; i++)
        {
            char ch = s.charAt(i);
            sb.append(ch >= 32 && ch < 127 ? ch : '?');
        }
        return sb.toString();
    }

    static void menu(Conn c) 
    {
        StringBuilder sb = new StringBuilder("MENU");
        synchronized (LOCK) 
        {
            for (String p : PREP.keySet())
                sb.append(' ').append(p).append(" (").append(PREP.get(p)).append("s) in stock: ").append(stock.get(p)).append(';');
        }
        c.send(sb.toString());
    }

    static Map<String, Integer> parseItems(String[] t) throws BadInput 
    {
        if (t.length < 3 || (t.length - 1) % 2 != 0) 
            throw new BadInput("usage: " + t[0].toUpperCase(Locale.ROOT) + " <product> <quantity> [<product> <quantity> ...]");
        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 1; i < t.length; i += 2) 
        {
            String p = t[i].toLowerCase(Locale.ROOT);
            if (!PREP.containsKey(p)) 
                throw new BadInput("unknown product (known: " + String.join(", ", PREP.keySet()) + ")");
            if (!t[i + 1].matches("[0-9]{1,7}")) 
                throw new BadInput("quantity of " + p + " must be a positive integer");
            int q = Integer.parseInt(t[i + 1]);
            if (q <= 0) 
                throw new BadInput("quantity of " + p + " must be a positive integer");
            if (q > MAX_QTY) 
                throw new BadInput("quantity too big (max " + MAX_QTY + ")");
            items.merge(p, q, Integer::sum);
        }
        return items;
    }

    static void order(Conn c, String[] t) 
    {
        Map<String, Integer> items;
        try 
        { 
            items = parseItems(t); 
        }
        catch (BadInput e) 
        { 
            log("ERROR " + c.name + ": bad order: " + e.getMessage()); 
            c.send("ERR " + e.getMessage()); 
            return; 
        }
        synchronized (LOCK) 
        {
            if (c.provider) 
            { 
                log("ERROR " + c.name + ": the provider tried to order");
                c.send("ERR the provider cannot order"); 
                return;
            }
            StringBuilder lack = new StringBuilder();
            for (Map.Entry<String, Integer> e : items.entrySet())
                if (e.getValue() > stock.get(e.getKey()))
                    lack.append(' ').append(e.getKey()).append(": asked ").append(e.getValue()).append(", left ").append(stock.get(e.getKey())).append(';');
            if (lack.length() > 0) 
            {
                log("order REFUSED for " + c.name + " " + items);
                c.send("REFUSED not enough stock:" + lack);
                return;
            }
            int secs = 0;
            List<String> fellLow = new ArrayList<>();
            for (Map.Entry<String, Integer> e : items.entrySet()) 
            {
                int before = stock.get(e.getKey()), after = before - e.getValue();
                stock.put(e.getKey(), after);
                secs += PREP.get(e.getKey()) * e.getValue();
                if (before >= LOW && after < LOW) 
                    fellLow.add(e.getKey());
            }
            Order o = new Order(++orderSeq, c, items, secs);
            c.orders.add(o);
            log("order #" + o.id + " ACCEPTED for " + c.name + " " + items + " (" + secs + " s)");
            c.send("ACCEPTED order=" + o.id + " time=" + secs + "s"); 
            queue.add(o);
            for (String p : fellLow) 
                alertProvider(p);
        }
    }

    static void alertProvider(String p) 
    {
        if (providerConn != null) 
        {
            providerConn.send("ALERT " + p + " is low: " + stock.get(p) + " left");
            log("alert sent to provider " + providerConn.name + ": " + p + " " + stock.get(p));
        }
    }

    static void becomeProvider(Conn c) 
    {
        synchronized (LOCK) 
        {
            if (c.provider) 
                { 
                    c.send("OK you are already the provider"); 
                    return; 
                }
            if (providerConn != null) 
                { 
                    log("ERROR " + c.name + ": wants to be provider, but " + providerConn.name + " already is");
                    c.send("ERR there is already a provider"); 
                    return; 
                }
            if (!c.orders.isEmpty()) 
                { 
                    log("ERROR " + c.name + ": wants to be provider but has orders");
                    c.send("ERR you have orders, cannot become the provider"); 
                    return; 
                }
            c.provider = true;
            providerConn = c;
            log("PROVIDER is " + c.name);
            c.send("OK you are the provider");
            for (String p : PREP.keySet()) 
                if (stock.get(p) < LOW) 
                    alertProvider(p);
        }
    }

    static void deliver(Conn c, String[] t) 
    {
        Map<String, Integer> items;
        try 
        { 
            items = parseItems(t); 
        }
        catch (BadInput e) 
        {
            log("ERROR " + c.name + ": bad delivery: " + e.getMessage()); 
            c.send("ERR " + e.getMessage()); 
            return; 
        }
        synchronized (LOCK) 
        {
            if (!c.provider) 
            { log("ERROR " + c.name + ": delivery by a non-provider"); 
                c.send("ERR only the provider can deliver"); 
                return; 
            }
            for (Map.Entry<String, Integer> e : items.entrySet())
                if ((long) stock.get(e.getKey()) + e.getValue() > MAX_STOCK) 
                { 
                    log("ERROR " + c.name + ": delivery over stock limit for " + e.getKey());
                    c.send("ERR stock limit reached for " + e.getKey()); 
                    return; 
                }
            for (Map.Entry<String, Integer> e : items.entrySet()) 
                stock.merge(e.getKey(), e.getValue(), Integer::sum);
            log("DELIVERY from " + c.name + " " + items + " -> stock " + stock);
            c.send("OK delivered, stock " + stock);
        }
    }
}