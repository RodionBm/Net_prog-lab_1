import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The console of the client: shows server messages without breaking the line
 * the user is typing.
 *
 * Normally the console is in "line mode": it echoes the keys itself and gives
 * the program the whole line only after Enter. Then the program cannot know
 * what is on the screen, and a message printed by the receiver thread lands in
 * the middle of the typed text.
 *
 * So the console is put in "raw mode": every key comes to the program at once
 * and is not echoed. The program keeps the typed text in {@code typed} and
 * echoes it itself. To show a message it clears the input line, prints the
 * message, and prints the prompt and the typed text again. One lock keeps the
 * keyboard echo and the messages from writing at the same time.
 *
 * Java 8 has no call for raw mode, so a small helper process sets it:
 *   Windows: PowerShell calls SetConsoleMode on the console it shares with us.
 *   Linux/macOS: stty -icanon -echo.
 * If the input or output is not a console, or raw mode cannot be set, it falls
 * back to plain line mode (messages are then printed as they come).
 */
final class Terminal
{
    static final String PROMPT = "> ";
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");

    private final Object lock = new Object();
    private final StringBuilder typed = new StringBuilder();
    private final PrintStream out = System.out;
    private final InputStream in = System.in;
    private BufferedReader lineReader;      // only in line mode
    private boolean raw;
    private int maxTyped = Integer.MAX_VALUE;
    private String savedMode;               // to restore the console at the end
    private boolean closed;

    private boolean lastWasCr;              // "\r\n" is one Enter
    private int escape;                     // skipping an arrow-key sequence (ESC [ A)

    Terminal()
    {
        if (System.console() != null && !"line".equals(System.getenv("CLIENT_CONSOLE")))
        {
            try
            {
                enterRaw();
            }
            catch (Exception e)
            {
                raw = false;
                if (savedMode != null)
                {
                    try
                    {
                        if (WINDOWS) windowsConsole(savedMode); else stty(savedMode);
                    }
                    catch (Exception ignored)
                    {
                    }
                }
                System.err.println("(line mode: messages may break typed text - " + e.getMessage() + ")");
            }
        }
        if (raw)
            Runtime.getRuntime().addShutdownHook(new Thread(this::close));
        else
            lineReader = new BufferedReader(new InputStreamReader(in));
    }

    /** Prints a message without losing the text being typed. */
    void show(String message)
    {
        show(message, true);
    }

    void show(String message, boolean promptAgain)
    {
        synchronized (lock)
        {
            if (!raw)
            {
                out.println(message);
                return;
            }
            out.print("\r" + spaces(PROMPT.length() + typed.length()) + "\r");
            out.println(message);
            if (promptAgain)
                out.print(PROMPT + typed);
            out.flush();
        }
    }

    /** Prints the first prompt. */
    void start()
    {
        synchronized (lock)
        {
            if (raw)
            {
                out.print(PROMPT);
                out.flush();
            }
        }
    }

    /** Returns the next line the user typed, or null when the input ends. */
    String readLine() throws IOException
    {
        if (!raw)
            return lineReader.readLine();
        while (true)
        {
            int b = in.read();
            if (b < 0)
                return null;
            synchronized (lock)
            {
                if (escape == 1)
                {
                    escape = (b == '[' || b == 'O') ? 2 : 0;
                    continue;
                }
                if (escape == 2)
                {
                    if (b >= 0x40 && b <= 0x7e) escape = 0;   // end of the sequence
                    continue;
                }
                boolean cr = b == '\r';
                if (b == '\n' && lastWasCr)
                {
                    lastWasCr = false;
                    continue;
                }
                lastWasCr = cr;
                if (cr || b == '\n')
                {
                    String line = typed.toString();
                    typed.setLength(0);
                    out.println();
                    out.print(PROMPT);
                    out.flush();
                    return line;
                }
                if (b == 8 || b == 127)                   // Backspace
                {
                    if (typed.length() > 0)
                    {
                        typed.setLength(typed.length() - 1);
                        out.print("\b \b");
                        out.flush();
                    }
                }
                else if (b == 27)                         // ESC: start of an arrow key on Linux
                    escape = 1;
                else if ((b == 4 || b == 26) && typed.length() == 0)
                    return null;                          // Ctrl+D / Ctrl+Z on an empty line
                else if (b >= 32 && b < 127 && typed.length() < maxTyped)
                {
                    typed.append((char) b);
                    out.print((char) b);
                    out.flush();
                }
            }
        }
    }

    /** Puts the console back the way it was. Safe to call more than once. */
    void close()
    {
        synchronized (lock)
        {
            if (!raw || closed)
                return;
            closed = true;
            out.println();
            out.flush();
        }
        try
        {
            if (WINDOWS)
                windowsConsole(savedMode);
            else
                stty(savedMode);
        }
        catch (Exception ignored)
        {
            // nothing more we can do
        }
    }

    private void enterRaw() throws Exception
    {
        int width;
        if (WINDOWS)
        {
            // answer: "<old mode> <window width>"
            String[] r = windowsConsole("raw").split(" ");
            savedMode = r[0];
            width = Integer.parseInt(r[1]);
        }
        else
        {
            savedMode = stty("-g").trim();
            stty("-icanon -echo min 1 time 0");
            String[] size = stty("size").trim().split("\\s+");   // "rows cols"
            width = Integer.parseInt(size[1]);
        }
        raw = true;
        // the input line must fit on one screen line, or it cannot be redrawn
        maxTyped = Math.max(10, width - PROMPT.length() - 1);
    }

    /**
     * Runs PowerShell, which calls GetConsoleMode/SetConsoleMode on the console
     * input it inherits from us. The mode belongs to the console, not to the
     * process, so it stays after the helper exits.
     * arg = "raw" (turn off line input and echo) or an old mode to restore.
     */
    private static String windowsConsole(String arg) throws Exception
    {
        String script =
            "$ProgressPreference='SilentlyContinue';"   // no progress records on stderr
          + "$s='[DllImport(\"kernel32.dll\")] public static extern IntPtr GetStdHandle(int h);"
          + "[DllImport(\"kernel32.dll\")] public static extern bool GetConsoleMode(IntPtr h, out int m);"
          + "[DllImport(\"kernel32.dll\")] public static extern bool SetConsoleMode(IntPtr h, int m);';"
          + "$k=Add-Type -MemberDefinition $s -Name Con -Namespace CoffeeClient -PassThru;"
          + "$h=$k::GetStdHandle(-10); $m=0;"
          + "if(-not $k::GetConsoleMode($h,[ref]$m)){exit 2};"
          + "$a='" + arg + "';"
          + "if($a -eq 'raw'){$n=$m -band (-bnot 6)}else{$n=[int]$a};"     // 2 = line input, 4 = echo
          + "if(-not $k::SetConsoleMode($h,$n)){exit 3};"
          + "$w=80; try{$w=[Console]::WindowWidth}catch{};"
          + "[Console]::Error.Write(\"ANSWER $m $w END\")";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);    // our console input
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);   // our console, for the width
        String answer = run(pb, true);                         // the answer comes on stderr
        Matcher m = Pattern.compile("ANSWER (\\d+) (\\d+) END").matcher(answer);
        if (!m.find())
            throw new IOException("console helper gave no answer: " + answer);
        return m.group(1) + " " + m.group(2);
    }

    private static String stty(String args) throws Exception
    {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", "stty " + args + " < /dev/tty");
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        return run(pb, false);
    }

    private static String run(ProcessBuilder pb, boolean fromStderr) throws Exception
    {
        Process p = pb.start();
        InputStream answer = fromStderr ? p.getErrorStream() : p.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[256];
        int n;
        while ((n = answer.read(chunk)) > 0)
            buf.write(chunk, 0, n);
        if (p.waitFor() != 0)
            throw new IOException("console helper failed (exit " + p.exitValue() + ")");
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String spaces(int n)
    {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(' ');
        return sb.toString();
    }
}
