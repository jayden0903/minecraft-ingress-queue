package io.github.jayden0903.fairqueue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

/** Previously dynamic routes must be visible after restart before anyone joins. */
public class RouteTrafficRestartTest {
    public static void main(String[] arguments) throws Exception {
        var directory=Files.createTempDirectory("traffic-restart-");
        var file=directory.resolve("traffic.properties");
        var old=new TrafficLedger(file);
        old.save(Map.of("s1.example.com",100L,"s6.example.com",900L));
        try(var tracker=new RouteTraffic(List.of("s1.example.com","s2.example.com"),file,LoggerFactory.getLogger(RouteTrafficRestartTest.class))){
            var state=tracker.state();
            check(state.keySet().containsAll(List.of("s1.example.com","s2.example.com","s6.example.com")),"Missing persisted route before reconnect");
            var restored=(Map<?,?>)state.get("s6.example.com");
            check(restored.get("txBytes").equals(900L),"Cumulative total was lost");
            check(restored.get("txWindowBytes").equals(0L),"Historical bytes contaminated the rolling window");
            check(tracker.measured()==0,"Test must not require live players");
        }
        var reloaded=new TrafficLedger(file);
        check(reloaded.total("s6.example.com",0)==900L,"Close/restart lost the dynamic route");
        try(var paths=Files.walk(directory)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}
        System.out.println("PASS: persisted dynamic routes appear with zero connections; rolling window starts empty");
    }
    private static void check(boolean valid,String reason){if(!valid)throw new AssertionError(reason);}
}
