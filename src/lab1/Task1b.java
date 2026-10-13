package lab1;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.locks.*;

public class Task1b 
{

    static int[] data;

    public static void main(String[] args) throws Exception 
    {
        Utils.printHardwareInfo();
        System.out.println("Loading " + Utils.FILE + " into memory ...");
        data = loadAll(Paths.get(Utils.FILE));
        System.out.println("Loaded " + data.length + " numbers.");
        long baseMem = Utils.baselineMemoryMB();
        System.out.println("Baseline memory: " + baseMem + " MB\n");
        int n = Runtime.getRuntime().availableProcessors();
        System.out.println("Logical processors: " + n + "\n");
        System.out.println("--- Version 1: shared, no synchronisation (3 runs) ---");
        for (int i = 1; i <= 3; i++) 
        {
            Utils.Counts c = new Utils.Counts();
            long ms = Utils.time(() -> runV1(n, c));
            System.out.printf("run %d: %d ms  ->  %s%n", i, ms, c);
        }
        System.out.println("\n--- Version 2: shared, lock-protected ---");
        for (int i = 1; i <= 1; i++) 
        {
            Utils.Counts c = new Utils.Counts();
            long ms = Utils.time(() -> runV2(n, c));
            System.out.printf("run %d: %d ms  ->  %s%n", i, ms, c);
        }
        System.out.println("\n--- Version 3: per-thread counters ---");
        for (int i = 1; i <= 1; i++) 
        {
            Utils.Counts c = new Utils.Counts();
            long ms = Utils.time(() -> runV3(n, c));
            System.out.printf("run %d: %d ms  ->  %s%n", i, ms, c);
        }
        long peak = Utils.usedMemoryMB();
        System.out.println("\nPeak memory (approx): " + (peak - baseMem) + " MB");
    }

    static int[] loadAll(Path p) throws IOException 
    {
        int[] arr = new int[Utils.TOTAL];
        int i = 0;
        try (BufferedReader r = Files.newBufferedReader(p)) 
        {
            String line;
            while ((line = r.readLine()) != null) 
            {
                if (i >= arr.length) break;
                arr[i++] = Utils.parse(line);
            }
        }
        return arr;
    }

    static void runV1(int n, Utils.Counts shared) 
    {
        Thread[] threads = new Thread[n];
        int chunk = data.length / n;
        for (int t = 0; t < n; t++) 
        {
            final int s = t * chunk;
            final int e = (t == n - 1) ? data.length : (t + 1) * chunk;
            threads[t] = new Thread(() -> 
            {
                for (int i = s; i < e; i++) 
                {
                    int v = data[i];
                    if (v < 0) shared.neg++;
                    else if (v > 0) shared.pos++;
                    else shared.zero++;
                }
            });
            threads[t].start();
        }
        joinAll(threads);
    }

    static void runV2(int n, Utils.Counts shared) 
    {
        ReentrantLock lock = new ReentrantLock();
        Thread[] threads = new Thread[n];
        int chunk = data.length / n;
        for (int t = 0; t < n; t++) 
        {
            final int s = t * chunk;
            final int e = (t == n - 1) ? data.length : (t + 1) * chunk;
            threads[t] = new Thread(() -> 
            {
                for (int i = s; i < e; i++) 
                {
                    int v = data[i];
                    lock.lock();
                    try 
                    {
                        if (v < 0) shared.neg++;
                        else if (v > 0) shared.pos++;
                        else shared.zero++;
                    } 
                    finally 
                    { 
                        lock.unlock(); 
                    }
                }
            });
            threads[t].start();
        }
        joinAll(threads);
    }

    static void runV3(int n, Utils.Counts shared) 
    {
        Utils.Counts[] local = new Utils.Counts[n];
        Thread[] threads = new Thread[n];
        int chunk = data.length / n;
        for (int t = 0; t < n; t++) 
        {
            final int idx = t;
            final int s = t * chunk;
            final int e = (t == n - 1) ? data.length : (t + 1) * chunk;
            threads[t] = new Thread(() -> 
            {
                Utils.Counts c = new Utils.Counts();
                for (int i = s; i < e; i++) 
                {
                    int v = data[i];
                    if (v < 0) c.neg++;
                    else if (v > 0) c.pos++;
                    else c.zero++;
                }
                local[idx] = c;
            });
            threads[t].start();
        }
        joinAll(threads);
        for (Utils.Counts c : local) if (c != null) shared.add(c);
    }

    static void joinAll(Thread[] ts) 
    {
        for (Thread t : ts) 
        {
            try
            { 
                t.join(); 
            } 
            catch (InterruptedException ignored) 
            {
                
            }
        }
    }
}