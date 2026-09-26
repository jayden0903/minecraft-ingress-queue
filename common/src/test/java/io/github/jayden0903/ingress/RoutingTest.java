package io.github.jayden0903.ingress;

import java.util.*;
import java.util.concurrent.*;

public final class RoutingTest {
    private static final RouteBalancer.Target A=new RouteBalancer.Target("s1.example.com",25565,true),B=new RouteBalancer.Target("s2.example.com",25565,true);
    private static long ms(long n){return n*1_000_000L;}
    private static RouteBalancer fresh(){var b=new RouteBalancer();b.configure(List.of(A,B),10000,5000);b.accept(Map.of(A.host(),0,B.host(),0),Map.of(),0);return b;}
    private static int pending(RouteBalancer b){return b.status(0,500).rows().stream().mapToInt(RouteBalancer.Row::pending).sum();}
    public static void main(String[] args) throws Exception {
        var b=fresh();assert b.reserve(UUID.randomUUID(),0).target().equals(A);assert b.reserve(UUID.randomUUID(),0).target().equals(B);
        b=fresh();b.traffic(Map.of(A.host(),10000000L,B.host(),1000000L),Map.of(A.host(),0L,B.host(),900000L),Map.of());
        assert b.reserve(UUID.randomUUID(),0).target().equals(B):"Cumulative totals must override opposite recent-traffic ranking";
        assert b.status(0,500).rows().get(0).txBytes()==10000000L;
        assert b.status(0,500).rows().get(0).txWindowBytes()==0L:"Recent chart metric must retain its meaning";
        b=fresh();b.traffic(Map.of(A.host(),Long.MAX_VALUE,B.host(),Long.MAX_VALUE),Map.of());
        assert b.reserve(UUID.randomUUID(),0).target().equals(A);assert b.reserve(UUID.randomUUID(),0).target().equals(B):"Saturated cumulative totals must not overflow";
        b=fresh();b.traffic(Map.of(A.host(),524288L,B.host(),0L),Map.of());
        assert b.reserve(UUID.randomUUID(),0).target().equals(B);assert b.reserve(UUID.randomUUID(),0).target().equals(B);
        assert b.reserve(UUID.randomUUID(),0).target().equals(A):"Reservations must spread arrivals once cumulative deficit is covered";
        b=fresh();b.accept(Map.of(A.host(),1,B.host(),20),Map.of(),0);b.traffic(Map.of(A.host(),2000000L,B.host(),1000L),Map.of());assert b.reserve(UUID.randomUUID(),0).target().equals(B);
        b=fresh();var pool=Executors.newFixedThreadPool(16);var shared=b;List<Future<?>> futures=new ArrayList<>();
        for(int i=0;i<1000;i++)futures.add(pool.submit(()->shared.reserve(UUID.randomUUID(),0)));
        for(var f:futures)f.get();pool.shutdown();var rows=b.status(0,500).rows();assert rows.get(0).pending()==500&&rows.get(1).pending()==500:rows;
        b.expire(ms(10000));assert pending(b)==0;
        b=fresh();UUID id=UUID.randomUUID();var reservation=b.reserve(id,0);assert pending(b)==1;
        b.accept(Map.of(A.host(),1,B.host(),0),Map.of(id,new RouteBalancer.Arrival(A.host(),"new-session")),ms(500));assert pending(b)==0;
        b=fresh();b.accept(Map.of(A.host(),1,B.host(),10),Map.of(id,new RouteBalancer.Arrival(A.host(),"old")),0);reservation=b.reserve(id,0);
        b.accept(Map.of(A.host(),1,B.host(),10),Map.of(id,new RouteBalancer.Arrival(A.host(),"old")),ms(500));assert pending(b)==1:"old connection cannot acknowledge new transfer";
        b.accept(Map.of(A.host(),1,B.host(),10),Map.of(id,new RouteBalancer.Arrival(A.host(),"new")),ms(1000));assert pending(b)==0;
        b=fresh();var first=b.reserve(id,0);var second=b.reserve(id,1);b.release(first);assert pending(b)==1;b.release(second);assert pending(b)==0;
        b=fresh();b.accept(Map.of(A.host(),100,B.host(),0),Map.of(),0);b.traffic(Map.of(A.host(),2000000L,B.host(),1000L),Map.of());assert b.status(ms(2000),500).mode().equals("LAST_GOOD");assert b.reserve(id,ms(2000)).target().equals(B);
        assert b.status(ms(5001),500).mode().equals("ROUND_ROBIN");assert b.reserve(UUID.randomUUID(),ms(5001)).target().equals(A);assert b.reserve(UUID.randomUUID(),ms(5001)).target().equals(B);
        b=fresh();b.configure(List.of(A,new RouteBalancer.Target(B.host(),25565,false),new RouteBalancer.Target("s6.example.com",25565,true)),10000,5000);
        assert b.reserve(UUID.randomUUID(),0).target().equals(A);assert b.reserve(UUID.randomUUID(),0).target().host().equals("s6.example.com");
        boolean failed=false;try{b.configure(List.of(A,A),10000,5000);}catch(IllegalArgumentException e){failed=true;}assert failed;assert b.status(0,500).rows().size()==3:"invalid reload must preserve state";
        System.out.println("PASS: least outbound traffic independent of player counts, 1000 concurrent reservations, RR ties/fallback, expiry, arrival acknowledgements, reconnect race, reload and dynamic routes");
    }
}
