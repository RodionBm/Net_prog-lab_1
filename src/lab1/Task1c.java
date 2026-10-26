package lab1;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class Task1c 
{
    static final int BATCH = 10_000;
    static final int QUEUE_CAP = 100;

    public static void main(String[] args) throws Exception 
    {
        Utils.printHardwareInfo();
        int[] workerCounts = {1, 2, 4, 8, 16, 32, 64};
        System.out.printf("%-10s %-12s %-14s %-14s%n",
                "workers", "time(ms)", "counts", "peakMem(MB)");
        for (int w : workerCounts) 
            runOnce(w);
    }

    static void runOnce(int nWorkers) throws Exception 
    {
        long baseMem = Utils.baselineMemoryMB();
        BlockingQueue<int[]> queue = new ArrayBlockingQueue<>(QUEUE_CAP);
        Utils.Counts total = new Utils.Counts();
        long[] peakMem = {0};
        Thread reader = new Thread(() -> 
        {
            try (BufferedReader r = Files.newBufferedReader(Paths.get(Utils.FILE))) 
            {
                int[] batch = new int[BATCH];
                int filled = 0;
                String line;
                while ((line = r.readLine()) != null) 
                {
                    batch[filled++] = Integer.parseInt(line.trim());
                    if (filled == BATCH) 
                    {
                        queue.put(batch);
                        batch = new int[BATCH];
                        filled = 0;
                    }
                }
                if (filled > 0) 
                {
                    int[] tail = new int[filled];
                    System.arraycopy(batch, 0, tail, 0, filled);
                    queue.put(tail);
                }
            } 
            catch (Exception e) 
            {
                e.printStackTrace();
            } 
            finally 
            {
                try 
                {
                    for (int i = 0; i < nWorkers; i++) 
                    {
                        queue.put(new int[0]);
                    }
                } 
                catch (InterruptedException ignored) 
                {

                }
            }
        });
        Thread[] workers = new Thread[nWorkers];
        Utils.Counts[] locals = new Utils.Counts[nWorkers];
        for (int i = 0; i < nWorkers; i++) 
        {
            final int idx = i;
            workers[i] = new Thread(() -> 
            {
                Utils.Counts c = new Utils.Counts();
                try 
                {
                    while (true) 
                    {
                        int[] b = queue.take();
                        if (b.length == 0) 
                            break;
                        for (int v : b) 
                        {
                            if (v < 0) 
                                c.neg++;
                            else if (v > 0) 
                                c.pos++;
                            else 
                                c.zero++;
                        }
                        long m = Utils.usedMemoryMB();
                        synchronized (peakMem) 
                        { 
                            if (m > peakMem[0]) 
                                peakMem[0] = m; 
                        }
                    }
                } 
                catch (InterruptedException ignored) 
                {

                }
                locals[idx] = c;
            });
            workers[i].start();
        }
        long t0 = System.nanoTime();
        reader.start();
        reader.join();
        for (Thread w : workers) 
            w.join();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        for (Utils.Counts c : locals) 
            if (c != null) 
                total.add(c);
        System.out.printf("%-10d %-12d %-14s %-14d%n", nWorkers, elapsedMs,
                "n=" + total.neg + " p=" + total.pos + " z=" + total.zero,
                peakMem[0] - baseMem);
    }
}