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
        System.out.printf("%-8s %-12s %-14s %-14s%n",
                "threads", "time(ms)", "counts", "peakMem(MB)");
        for (int n : threadCounts) 
        {
            runOnce(n);
        }
    }

    static void runOnce(int nThreads) throws Exception 
    {
        Path file = Paths.get(Utils.FILE);
        long size = Files.size(file);
        long[] starts = new long[nThreads + 1];
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) 
        {
            long chunk = size / nThreads;
            starts[0] = 0;
            for (int i = 1; i < nThreads; i++) 
            {
                starts[i] = snapForward(ch, i * chunk, size);
            }
            starts[nThreads] = size;
        }
        long baseMem = Utils.baselineMemoryMB();
        Utils.Counts[] perThread = new Utils.Counts[nThreads];
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(nThreads);
        long[] peakMem = {0};
        for (int i = 0; i < nThreads; i++) 
        {
            final int idx = i;
            final long s = starts[i];
            final long e = starts[i + 1];
            pool.submit(() -> 
            {
                try 
                {
                    start.await();
                    Utils.Counts c = countRange(file, s, e);
                    perThread[idx] = c;
                    long m = Utils.usedMemoryMB();
                    synchronized (peakMem) 
                    { 
                        if (m > peakMem[0]) 
                            peakMem[0] = m; 
                    }
                } 
                catch (Exception ex) 
                {
                    ex.printStackTrace();
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
        for (Utils.Counts c : perThread) 
            total.add(c);
        System.out.printf("%-8d %-12d %-14s %-14d%n", nThreads, elapsedMs,
                "n=" + total.neg + " p=" + total.pos + " z=" + total.zero,
                peakMem[0] - baseMem);
    }

    static long snapForward(FileChannel ch, long raw, long size) throws IOException 
    {
        if (raw <= 0) 
            return 0;
        if (raw >= size) 
            return size;
        ByteBuffer probe = ByteBuffer.allocate(1);
        long p = raw - 1;
        while (p < size) 
        {
            probe.clear();
            ch.read(probe, p);
            probe.flip();
            if (probe.get() == '\n') 
                return p + 1;
            p++;
        }
        return size;
    }

    static Utils.Counts countRange(Path file, long start, long end) throws IOException 
    {
        long len = end - start;
        if (len <= 0) 
            return new Utils.Counts();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) 
        {
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
                    if (hasDigit) Utils.classify(negative ? -((int) num) : (int) num, c);
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
            if (hasDigit) Utils.classify(negative ? -((int) num) : (int) num, c);
            return c;
        }
    }
}