package io.github.jayden0903.fairqueue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
/** Network-wide join/leave line; warm palette shared with the queue UI. */
final class PresenceMessage {
 static Component create(String username,boolean admin,boolean join) {
  TextColor bracket=TextColor.color(0x9AA3AD);
  return Component.text("[",bracket)
   .append(Component.text(join?"+":"-",TextColor.color(join?0x85D6A0:0xEF8585)))
   .append(Component.text("] [",bracket))
   .append(Component.text(admin?"ADMIN":"USER",TextColor.color(admin?0xFF8F88:0xFFC99E)))
   .append(Component.text("] ",bracket))
   .append(Component.text(username,TextColor.color(0xFFFFFF)));
 }
 /**
  * Priority member's name for the custom join line: the solid colour, or one Component per code point
  * interpolated from gradient.from (first) to gradient.to (last) when a gradient is set.
  */
 static Component name(String username,TextColor solid,PrioritySnapshot.Gradient gradient) {
  if(gradient==null||username.isEmpty())return Component.text(username,solid);
  int[] points=username.codePoints().toArray();
  var out=Component.text();
  for(int i=0;i<points.length;i++){
   float t=points.length==1?0f:i/(float)(points.length-1);
   out.append(Component.text(new String(points,i,1),TextColor.lerp(t,gradient.from(),gradient.to())));
  }
  return out.build();
 }
}
