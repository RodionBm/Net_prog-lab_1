package lab1;

public class Utils 
{
    public static final String FILE = "numbers.txt";
    public static final int TOTAL = 50_000_000;
    public static final int MIN = -1_000_000;
    public static final int MAX = 1_000_000;
    public static class Counts 
    {
        public long neg, pos, zero;
        
        public Counts() 
        {

        }
        
        public Counts(long n, long p, long z) 
        { 
            neg = n; pos = p; zero = z; 
        }
        
        public void add(Counts o) 
        { 
            neg += o.neg; pos += o.pos; zero += o.zero; 
        }
        
        public String toString() 
        {
            return String.format("neg=%d pos=%d zero=%d total=%d",
                    neg, pos, zero, neg + pos + zero);
        }
        
        public boolean equals(Object o) 
        {
            if (!(o instanceof Counts)) 
                return false;
            Counts c = (Counts) o;
            return neg == c.neg && pos == c.pos && zero == c.zero;
        }
    }

    public static void classify(int v, Counts c) 
    {
        if (v < 0) 
            c.neg++;
        else if (v > 0) 
            c.pos++;
        else 
            c.zero++;
    }

    public static int parse(String line) 
    {
        return Integer.parseInt(line.trim());
    }

    public static long time(Runnable r) 
    {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1_000_000;
    }

    public static long usedMemoryMB() 
    {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    public static long baselineMemoryMB() 
    {
        System.gc();
        try 
        { 
            Thread.sleep(200); 
        } 
        catch (InterruptedException ignored) 
        {

        }
        return usedMemoryMB();
    }

    public static void printHardwareInfo() 
    {
        System.out.println("=== Hardware / Runtime ===");
        System.out.println("OS         : " + System.getProperty("os.name")
                + " " + System.getProperty("os.version"));
        System.out.println("Arch       : " + System.getProperty("os.arch"));
        System.out.println("Java       : " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vendor") + ")");
        System.out.println("Logical CPU: " + Runtime.getRuntime().availableProcessors());
        System.out.println("Max heap   : "
                + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        System.out.println();
    }
}