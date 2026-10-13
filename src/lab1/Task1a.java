package lab1;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class Task1a 
{

    public static void main(String[] args) throws Exception 
    {
        Utils.printHardwareInfo();
        int[] threadCounts = {1, 2, 4, 8, 16, 32, 64};
        System.out.printf("%-8s %-12s %-14s %-14s%n", "threads", "time(ms)", "counts", "peakMem(MB)");
        for (int n : threadCounts) 
        {
            runOnce(n);
        }
    }

    static void runOnce(int nThreads) throws Exception
    {
        Path file = Paths.get(Utils.FILE);
        long size = Files.size(file);
        long baseMem = Utils.baselineMemoryMB();
        Utils.Counts[] perThread = new Utils.Counts[nThreads];
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(nThreads);
        long[] peakMem = {0};
        long chunk = size / nThreads;
        for (int i = 0; i < nThreads; i++) 
        {
            long start0 = i * chunk;
            long end0 = (i == nThreads - 1) ? size : (i + 1) * chunk;
            final int idx = i;
            pool.submit(() -> 
            {
                try 
                {
                    start.await();
                    Utils.Counts c = countRange(file, start0, end0);
                    perThread[idx] = c;
                    long m = Utils.usedMemoryMB();
                    synchronized (peakMem) { if (m > peakMem[0]) peakMem[0] = m; }
                } 
                catch (Exception e) 
                {
                    e.printStackTrace();
                } 
                finally 
                {
                    done.countDown();
                }
            });
        }
        long t0 = System.nanoTime();
        start.countDown();
        done.await();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        Utils.Counts total = new Utils.Counts();
        for (Utils.Counts c : perThread) total.add(c);
        System.out.printf("%-8d %-12d %-14s %-14d%n", nThreads, elapsedMs, "n=" + total.neg + " p=" + total.pos + " z=" + total.zero, peakMem[0] - baseMem);
    }

    static Utils.Counts countRange(Path file, long start, long end) throws IOException 
    {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) 
        {
            long size = ch.size();
            if (start > 0) 
            {
                long s = start - 1;
                ByteBuffer probe = ByteBuffer.allocate(1);
                while (s >= 0) 
                {
                    probe.clear();
                    ch.read(probe, s);
                    probe.flip();
                    if (probe.get() == '\n') 
                    { 
                        start = s + 1; 
                        break; 
                    }
                    s--;
                }
            }
            if (end < size) 
            {
                long e = end;
                ByteBuffer probe = ByteBuffer.allocate(1);
                while (e < size) 
                {
                    probe.clear();
                    ch.read(probe, e);
                    probe.flip();
                    if (probe.get() == '\n') 
                    { 
                        end = e + 1; 
                        break; 
                    }
                    e++;
                }
                if (e >= size) end = size;
            }
            long len = end - start;
            if (len <= 0) 
                return new Utils.Counts();
            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_ONLY, start, len);
            Utils.Counts c = new Utils.Counts();
            long num = 0;
            boolean negative = false;
            boolean hasDigit = false;
            while (buf.hasRemaining()) 
            {
                byte b = buf.get();
                if (b == '\n') 
                {
                    if (hasDigit) 
                        Utils.classify(negative ? -((int) num) : (int) num, c);
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
                Utils.classify(negative ? -((int) num) : (int) num, c);
            return c;
        }
    }
}