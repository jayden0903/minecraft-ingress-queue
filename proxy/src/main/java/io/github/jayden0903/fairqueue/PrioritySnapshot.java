package io.github.jayden0903.fairqueue;
import com.google.gson.*;
import net.kyori.adventure.text.format.TextColor;
import java.nio.file.*;
import java.util.*;
/** Cosmetic and queue entitlements only; never authentication or access permissions. */
final class PrioritySnapshot {
 /** Optional two-stop name gradient for join messages. */
 record Gradient(TextColor from,TextColor to) {}
 /** gradient is null when the optional field is missing or invalid. */
 record Member(long expires,String color,String joining,Gradient gradient) {}
 private volatile Map<UUID,Member> members=Map.of();
 void read(Path file)throws Exception{
  if(!Files.exists(file))return;if(Files.size(file)>4*1024*1024)throw new IllegalArgumentException("Snapshot too large");
  var root=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(root.get("version").getAsInt()!=1)throw new IllegalArgumentException();
  var next=new HashMap<UUID,Member>();for(var e:root.getAsJsonObject("members").entrySet()){
   var o=e.getValue().getAsJsonObject();String color=o.get("color").getAsString(),joining=o.get("joining").getAsString();
   if(!color.matches("[A-Fa-f0-9]{6}")||!Set.of("기본","인사","반가워요","함께해요","끄기").contains(joining))throw new IllegalArgumentException();
   next.put(UUID.fromString(e.getKey()),new Member(o.get("expires").getAsLong(),color,joining,gradient(o.get("gradient"))));
  }members=Map.copyOf(next);
 }
 /** Tolerant parse of ["RRGGBB","RRGGBB"]; anything else (missing, wrong shape, bad hex) is null, never an error. */
 static Gradient gradient(JsonElement value){
  if(value==null||!value.isJsonArray())return null;var a=value.getAsJsonArray();if(a.size()!=2)return null;
  TextColor from=hex(a.get(0)),to=hex(a.get(1));return from==null||to==null?null:new Gradient(from,to);
 }
 private static TextColor hex(JsonElement value){
  if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())return null;
  String s=value.getAsString();if(s.startsWith("#"))s=s.substring(1);
  return s.matches("[A-Fa-f0-9]{6}")?TextColor.color(Integer.parseInt(s,16)):null;
 }
 Member active(UUID uid){Member m=members.get(uid);return m!=null&&m.expires>System.currentTimeMillis()/1000?m:null;}
 Map<UUID,Long> priorities(){var out=new HashMap<UUID,Long>();members.forEach((uid,m)->out.put(uid,m.expires));return out;}
}
