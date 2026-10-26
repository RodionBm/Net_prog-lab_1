package lab1;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class Task2 
{
    public static void main(String[] args) throws Exception 
    {
        Utils.printHardwareInfo();
        String folder = "parts";
        int onlyThreads = 0;
        for (int i = 0; i < args.length; i++) 
        {
            if (args[i].equals("--only") && i + 1 < args.length) 
            {
                onlyThreads = Integer.parseInt(args[++i]);
            } 
            else 
            {
                folder = args[i];
            }
        }
        Path dir = Paths.get(folder);
        Path[] files = new Path[8];
        for (int i = 0; i < 8; i++) 
            files[i] = dir.resolve("part" + i + ".txt");
        for (Path f : files) 
        {
            if (!Files.exists(f)) 
            {
                System.err.println("Missing: " + f + "  (run DataGenerator first)");
                return;
            }
        }
        int[] threadCounts = (onlyThreads > 0)
                ? new int[]{ onlyThreads }
                : new int[]{ 1, 2, 4 };
        System.out.printf("%-8s %-12s %-14s %-14s%n",
                "threads", "time(ms)", "counts", "peakMem(MB)");
        for (int n : threadCounts) 
            runOnce(files, n);
    }

    static void runOnce(Path[] files, int nThreads) throws Exception 
    {
        long baseMem = Utils.baselineMemoryMB();
        Utils.Counts[] perThread = new Utils.Counts[nThreads];
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        CountDownLatch done = new CountDownLatch(nThreads);
        long[] peakMem = {0};
        BlockingQueue<Integer> fileQueue = new LinkedBlockingQueue<>();
        for (int i = 0; i < files.length; i++) 
            fileQueue.add(i);
        long t0 = System.nanoTime();
        for (int t = 0; t < nThreads; t++) 
        {
            final int idx = t;
            pool.submit(() -> 
            {
                Utils.Counts c = new Utils.Counts();
                try 
                {
                    Integer fi;
                    while ((fi = fileQueue.poll()) != null) 
                    {
                        Utils.Counts fc = readOneFile(files[fi]);
                        c.add(fc);
                    }
                } 
                catch (Exception e) 
                {
                    e.printStackTrace();
                } 
                finally 
                {
                    perThread[idx] = c;
                    long m = Utils.usedMemoryMB();
                    synchronized (peakMem) { if (m > peakMem[0]) peakMem[0] = m; }
                    done.countDown();
                }
            });
        }
        done.await();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        Utils.Counts total = new Utils.Counts();
        for (Utils.Counts c : perThread) 
            if (c != null) 
                total.add(c);
        System.out.printf("%-8d %-12d %-14s %-14d%n", nThreads, elapsedMs,
                "n=" + total.neg + " p=" + total.pos + " z=" + total.zero,
                peakMem[0] - baseMem);
    }

    static Utils.Counts readOneFile(Path p) throws IOException 
    {
        byte[] buf = Files.readAllBytes(p);
        Utils.Counts c = new Utils.Counts();
        long num = 0;
        boolean negative = false;
        boolean hasDigit = false;
        for (byte b : buf) 
        {
            if (b == '\n' || b == '\r') 
            {
                if (hasDigit) 
                {
                    long v = negative ? -num : num;
                    if (v < 0) 
                        c.neg++;
                    else if (v > 0) 
                        c.pos++;
                    else 
                        c.zero++;
                }
                num = 0; negative = false; hasDigit = false;
            } 
            else if (b == '-') 
            {
                negative = true;
            } 
            else if (b >= '0' && b <= '9') 
            {
                num = num * 10 + (b - '0');
                hasDigit = true;
            }
        }
        if (hasDigit) 
        {
            long v = negative ? -num : num;
            if (v < 0) 
                c.neg++;
            else if (v > 0) 
                c.pos++;
            else 
                c.zero++;
        }
        return c;
    }
}