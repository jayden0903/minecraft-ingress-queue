package io.github.jayden0903.fairqueue;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
public class ServerBrandTest {
 public static void main(String[] args){
  var brand=new ServerBrand("§6example.com§r");var c=new EmbeddedChannel(brand);
  var raw=Unpooled.buffer();ProtocolUtils.writeString(raw,"Folia (Velocity)");
  var original=new PluginMessagePacket("minecraft:brand",raw);
  assert c.writeOutbound(original);assert raw.refCnt()==0;
  PluginMessagePacket result=c.readOutbound();assert result.getChannel().equals("minecraft:brand");
  assert ProtocolUtils.readString(result.content()).equals(brand.label());result.release();
  var other=new PluginMessagePacket("example:unrelated",Unpooled.wrappedBuffer(new byte[]{1,2,3}));
  assert c.writeOutbound(other);assert c.readOutbound()==other;assert other.content().readByte()==1;other.release();
  Object packet=new Object();assert c.writeOutbound(packet);assert c.readOutbound()==packet;
  c.finishAndReleaseAll();System.out.println("PASS: server brand replacement, original buffer release, unrelated traffic unchanged");
 }
}
