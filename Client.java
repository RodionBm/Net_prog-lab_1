import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Client and provider in one program.
 *   java Client <host> <port>            -> client
 *   java Client <host> <port> provider   -> provider
 *
 * Three threads:
 *   main      reads the keyboard and sends the commands;
 *   receiver  prints server messages as soon as they arrive (see Terminal:
 *             they do not break the line being typed);
 *   heartbeat sends PING every second. The server answers PONG.
 *
 * Why a heartbeat: when the network is lost (cable out, Wi-Fi off) nothing
 * arrives, not even a close, so a plain receive would wait forever. With PONG
 * coming every second, the receive has a timeout: no data for 5 s means the
 * server is unreachable, and the client ends.
 */
public class Client
{
    static final int HEARTBEAT_MS = 1000;
    static final int SILENCE_LIMIT_MS = 5000;

    static volatile boolean quitting = false;
    static final Object SEND_LOCK = new Object();
    static OutputStream os;
    static Terminal term;

    public static void main(String[] args)
    {
        if (args.length < 2 || args.length > 3)
        {
            System.err.println("Usage: java Client <host> <port> [provider]");
            return;
        }
        int port;
        try
        {
            port = Integer.parseInt(args[1]);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        }
        catch (NumberFormatException e)
        {
            System.err.println("Bad port: " + args[1] + " (must be 1..65535)");
            return;
        }
        boolean provider = args.length > 2 && args[2].equalsIgnoreCase("provider");
        Socket s;
        try
        {
            s = new Socket(args[0], port);
            s.setTcpNoDelay(true);
            s.setSoTimeout(SILENCE_LIMIT_MS);   // receive fails if nothing arrives for 5 s
            os = s.getOutputStream();
        }
        catch (Exception e)
        {
            System.err.println("Cannot connect: " + e);
            System.exit(1);
            return;
        }
        term = new Terminal();
        System.out.println("Connected to " + s.getRemoteSocketAddress() + ". Commands: MENU, "
                + (provider ? "DELIVER <product> <qty> ..., " : "ORDER <product> <qty> ..., ") + "QUIT");

        Thread receiver = daemon(() -> receive(s));
        daemon(Client::heartbeat);
        term.start();
        try
        {
            if (provider)
                send("PROVIDER");
            String line;
            while ((line = term.readLine()) != null)
            {
                if (line.trim().isEmpty()) continue;
                boolean quit = line.trim().equalsIgnoreCase("QUIT");
                if (quit) quitting = true;
                send(line);
                if (quit)
                {
                    receiver.join(3000);   // let BYE arrive, then the server closes
                    break;
                }
            }
        }
        catch (Exception e)
        {
            term.show("Connection error: " + e.getMessage(), false);
        }
        quitting = true;
        try { s.close(); } catch (IOException ignored) { }
        System.exit(0);                // the shutdown hook puts the console back
    }

    static Thread daemon(Runnable body)
    {
        Thread t = new Thread(body);
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Receiver thread: cuts the byte stream into lines and shows them. */
    static void receive(Socket s)
    {
        String reason = "Server disconnected.";
        try
        {
            LineReader r = new LineReader(s.getInputStream(), 4096);
            String line;
            while ((line = r.readLine()) != null)
            {
                if (line.equals("PONG")) continue;        // heartbeat answer, not for the user
                term.show(line == LineReader.TOO_LONG ? "[server line too long]" : line);
            }
        }
        catch (SocketTimeoutException e)
        {
            reason = "Server unreachable: nothing received for " + SILENCE_LIMIT_MS / 1000
                    + " s, the network is lost.";
        }
        catch (IOException e)
        {
            reason = "Connection to the server lost (" + e.getMessage() + ").";
        }
        if (quitting) return;           // we asked to leave: main ends the program
        term.show(reason + " Closing.", false);
        System.exit(0);
    }

    /** Heartbeat thread: one PING per second keeps PONGs coming back. */
    static void heartbeat()
    {
        try
        {
            while (true)
            {
                Thread.sleep(HEARTBEAT_MS);
                send("PING");
            }
        }
        catch (Exception e)
        {
            if (quitting) return;
            term.show("Connection to the server lost (" + e.getMessage() + "). Closing.", false);
            System.exit(0);
        }
    }

    /** Two threads send (main and heartbeat), so one lock keeps lines whole. */
    static void send(String line) throws IOException
    {
        byte[] data = (line + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (SEND_LOCK)
        {
            os.write(data);
        }
    }
}
