package io.github.jayden0903.fairqueue;

import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.PluginMessagePacket;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

/** Rewrites only outgoing server brand, after Velocity adds its suffix, before encoding. */
final class ServerBrand extends ChannelOutboundHandlerAdapter {
    private final String label;
    ServerBrand(String label){this.label=java.util.Objects.requireNonNull(label);}
    String label(){return label;}
    @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise)throws Exception {
        if(message instanceof PluginMessagePacket packet && "minecraft:brand".equals(packet.getChannel())) {
            ByteBuf data=ctx.alloc().buffer();
            try { ProtocolUtils.writeString(data,label); }
            catch(Throwable e){data.release();throw e;}
            message=new PluginMessagePacket(packet.getChannel(),data);
            ReferenceCountUtil.release(packet);
        }
        ctx.write(message,promise);
    }
}
