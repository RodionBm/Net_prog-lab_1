package lab1;

import java.io.*;
import java.nio.file.*;
import java.util.Random;

public class DataGenerator 
{

    public static void main(String[] args) throws IOException 
    {
        generate();
        splitInto8();
    }

    static void generate() throws IOException 
    {
        Path p = Paths.get(Utils.FILE);
        if (Files.exists(p) && Files.size(p) > 100_000_000L) 
        {
            System.out.println(Utils.FILE + " already exists, skipping generation.");
            return;
        }
        System.out.println("Generating " + Utils.FILE + " ...");
        Random rnd = new Random(42);
        try (BufferedWriter w = Files.newBufferedWriter(p)) 
        {
            for (int i = 0; i < Utils.TOTAL; i++) 
            {
                int v = rnd.nextInt(Utils.MAX - Utils.MIN + 1) + Utils.MIN;
                w.write(Integer.toString(v));
                w.write('\n');
            }
        }
        System.out.println("Done.");
    }

    static void splitInto8() throws IOException 
    {
        Path dir = Paths.get("parts");
        Files.createDirectories(dir);
        System.out.println("Splitting into 8 files of 625000 numbers each ...");
        final int LINES_PER_PART = 625_000;
        try (BufferedReader r = Files.newBufferedReader(Paths.get(Utils.FILE))) 
        {
            for (int part = 0; part < 8; part++) 
            {
                Path out = dir.resolve("part" + part + ".txt");
                try (BufferedWriter w = Files.newBufferedWriter(out)) 
                {
                    for (int i = 0; i < LINES_PER_PART; i++) 
                    {
                        String line = r.readLine();
                        if (line == null) 
                            throw new IOException("Input file too short");
                        w.write(line);
                        w.write('\n');
                    }
                }
            }
        }
        System.out.println("Done.");
    }
}