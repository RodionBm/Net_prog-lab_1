import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Cuts a TCP byte stream into lines ending with '\n'.
 * TCP has no message boundaries: one receive may hold half a message or
 * several messages. Bytes are buffered until '\n' arrives; leftover bytes
 * after '\n' stay in buf for the next call.
 */
final class LineReader
{
    static final String TOO_LONG = new String("<too long>");
    private final InputStream in;
    private final int maxBytes;
    private final byte[] buf = new byte[4096];
    private int pos = 0, lim = 0;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private boolean overflow = false;   // discarding the rest of a too long line

    LineReader(InputStream in, int maxBytes)
    {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /** Returns the next line, TOO_LONG once for a too long line, or null at end of stream. */
    String readLine() throws IOException
    {
        while (true)
        {
            while (pos < lim)
            {
                byte b = buf[pos++];
                if (b == '\n')
                {
                    if (overflow)
                    {                       // end of the too long line, already reported
                        overflow = false;
                        continue;
                    }
                    byte[] bytes = line.toByteArray();
                    line.reset();
                    int n = bytes.length;
                    if (n > 0 && bytes[n - 1] == '\r') n--;
                    return new String(bytes, 0, n, StandardCharsets.UTF_8);
                }
                if (overflow)
                    continue;                // drop bytes until the end of the line
                if (line.size() >= maxBytes)
                {                            // report at once, without waiting for '\n'
                    overflow = true;
                    line.reset();
                    return TOO_LONG;
                }
                line.write(b);
            }
            int r = in.read(buf, 0, buf.length);   // receive; -1 = peer closed (recv returned 0)
            if (r < 0) return null;
            pos = 0;
            lim = r;
        }
    }
}
