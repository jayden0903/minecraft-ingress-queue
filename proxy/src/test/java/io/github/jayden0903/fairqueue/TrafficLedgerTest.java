package io.github.jayden0903.fairqueue;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import org.slf4j.LoggerFactory;

public final class TrafficLedgerTest {
    public static void main(String[] args) throws Exception {
        Path dir=Files.createTempDirectory("traffic-test-");
        try {
            String a="s1.example.com",b="s2.example.com",c="s6.example.com";
            Path file=dir.resolve("traffic.properties");
            TrafficLedger first=new TrafficLedger(file);
            first.save(Map.of(a,5_000_000_000L,b,123L));
            TrafficLedger restarted=new TrafficLedger(file);
            assert restarted.total(a,0)==5_000_000_000L;
            assert restarted.total(a,10)==5_000_000_010L;
            restarted.save(Map.of(a,10L,c,25L));
            TrafficLedger again=new TrafficLedger(file);
            assert again.total(a,0)==5_000_000_010L;
            assert again.total(b,0)==123 : "removed route retained";
            assert again.total(c,0)==25 : "new routes supported";
            again.save(Map.of(a,0L));
            assert new TrafficLedger(file).total(a,0)==5_000_000_010L : "no double count on repeated startup";

            Files.writeString(file,"schema=1\ntotal.s1.example.com=0\nsha256=bad\n");
            TrafficLedger recovered=new TrafficLedger(file);
            assert recovered.total(a,0)==5_000_000_010L : "checksum failure recovers backup";
            recovered.save(Map.of(a,1L));
            assert TrafficLedger.read(file).get(a)==5_000_000_011L;
            // A crash between the two atomic replacements can leave backup newer than primary.
            Files.write(file.resolveSibling(file.getFileName()+".bak"),TrafficLedger.encode(Map.of(a,5_000_000_020L,b,123L,c,25L)));
            assert new TrafficLedger(file).total(a,0)==5_000_000_020L;

            Path rolling=dir.resolve("rolling.properties");
            new TrafficLedger(rolling).save(Map.of(a,8_000_000_000L));
            try(RouteTraffic traffic=new RouteTraffic(List.of(a,b),rolling,LoggerFactory.getLogger("TrafficTest"))){
                Map<?,?> route=(Map<?,?>)traffic.state().get(a);
                assert ((Number)route.get("txBytes")).longValue()==8_000_000_000L;
                assert ((Number)route.get("txWindowBytes")).longValue()==0 : "restored bytes must not affect routing window";
                assert ((Number)route.get("txBytesPerSecond")).doubleValue()==0;
                var field=RouteTraffic.class.getDeclaredField("totals");field.setAccessible(true);
                @SuppressWarnings("unchecked") var counters=(Map<String,LongAdder>)field.get(traffic);
                try(ExecutorService pool=Executors.newFixedThreadPool(8)){
                    List<Future<?>> work=new ArrayList<>();
                    for(int i=0;i<8;i++)work.add(pool.submit(()->{for(int j=0;j<10000;j++)counters.get(a).add(10);}));
                    for(var job:work)job.get();
                }
                // Exercise the real scheduled checkpoint instead of manually invoking persistence.
                long deadline=System.nanoTime()+5_000_000_000L;
                while(new TrafficLedger(rolling).total(a,0)!=8_000_800_000L&&System.nanoTime()<deadline)Thread.sleep(50);
                assert new TrafficLedger(rolling).total(a,0)==8_000_800_000L;
                counters.get(a).add(7); // close() must flush bytes not yet checkpointed
            }
            assert new TrafficLedger(rolling).total(a,0)==8_000_800_007L;
            try(RouteTraffic traffic=new RouteTraffic(List.of(a),rolling,LoggerFactory.getLogger("TrafficTest"))){
                var route=(Map<?,?>)traffic.state().get(a);
                assert ((Number)route.get("txBytes")).longValue()==8_000_800_007L;
                assert ((Number)route.get("txWindowBytes")).longValue()==0;
            }
            Files.writeString(file,"corrupt");Files.writeString(file.resolveSibling(file.getFileName()+".bak"),"also corrupt");
            try{new TrafficLedger(file);throw new AssertionError("must not silently reset corrupt checkpoints");}catch(java.io.IOException expected){}
            assert Files.readString(file).equals("corrupt");
            System.out.println("PASS: restart/large totals/new and removed routes/checksum backup/interrupted replacement/concurrent counting/periodic and shutdown flush/rolling isolation/corrupt-file preservation");
        } finally {try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
