package io.github.jayden0903.ingress;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.util.CachedServerIcon;

/** A bounded loopback status request, used only on the dedicated status worker. */
public record BackendStatus(Component motd,int online,int maximum,String version,int protocol,CachedServerIcon icon,long sampledAt) {
    public static BackendStatus read(int port,int protocol) throws IOException {
        try(Socket socket=new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1",port),1000);socket.setSoTimeout(1000);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream hs=new DataOutputStream(buffer);
            variable(hs,0);variable(hs,protocol);byte[] host="127.0.0.1".getBytes(StandardCharsets.UTF_8);
            variable(hs,host.length);hs.write(host);hs.writeShort(port);variable(hs,1);
            variable(out,buffer.size());out.write(buffer.toByteArray());out.writeByte(1);out.writeByte(0);out.flush();
            DataInputStream in=new DataInputStream(socket.getInputStream());int size=variable(in);
            if(size<3||size>262144)throw new IOException("Invalid status frame size");
            byte[] frame=in.readNBytes(size);if(frame.length!=size)throw new EOFException();
            DataInputStream packet=new DataInputStream(new ByteArrayInputStream(frame));
            if(variable(packet)!=0)throw new IOException("Unexpected status packet");
            int textSize=variable(packet);if(textSize<1||textSize!=packet.available())throw new IOException("Invalid status JSON size");
            JsonObject root=JsonParser.parseString(new String(packet.readNBytes(textSize),StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject players=root.getAsJsonObject("players"),version=root.getAsJsonObject("version");
            int online=players.get("online").getAsInt(),max=players.get("max").getAsInt();
            if(online<0||max<0)throw new IOException("Invalid player count");
            String favicon=root.has("favicon")?root.get("favicon").getAsString():null;
            CachedServerIcon icon=null;
            if(favicon!=null&&favicon.startsWith("data:image/png;base64,")) {
                try {
                    byte[] png=java.util.Base64.getDecoder().decode(favicon.substring("data:image/png;base64,".length()));
                    var image=javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
                    if(image==null||image.getWidth()!=64||image.getHeight()!=64)throw new IOException("Invalid server icon");
                    // Paper requires its native cached icon implementation when serializing status.
                    icon=org.bukkit.Bukkit.loadServerIcon(image);
                }catch(Exception e){throw new IOException("Cannot load backend server icon",e);}
            }
            return new BackendStatus(GsonComponentSerializer.gson().deserialize(root.get("description").toString()),online,max,version.get("name").getAsString(),version.get("protocol").getAsInt(),icon,System.nanoTime());
        }
    }
    private static int variable(DataInputStream in) throws IOException {
        int n=0;for(int i=0;i<5;i++){int b=in.readUnsignedByte();n|=(b&127)<<(i*7);if((b&128)==0)return n;}throw new IOException("Invalid VarInt");
    }
    private static void variable(DataOutputStream out,int n) throws IOException {
        do{int b=n&127;n>>>=7;out.writeByte(b|(n!=0?128:0));}while(n!=0);
    }
}
