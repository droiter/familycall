import com.familycall.app.net.UdpSocket;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;

public final class UdpSocketSelfTest {
    public static void main(String[] args) throws Exception {
        UdpSocket a = new UdpSocket(50111);
        UdpSocket b = new UdpSocket(50112);
        a.open();
        b.open();
        a.setPeer(new InetSocketAddress("127.0.0.1", 50112));
        b.setPeer(new InetSocketAddress("127.0.0.1", 50111));
        byte[] out = {1, 2, 3, 4, 5};
        if (!a.send(out, out.length)) throw new AssertionError("send a");
        DatagramPacket p = b.receive(new byte[64]);
        if (p.getLength() != out.length) throw new AssertionError("len");
        // Rebind (network switch simulation) and verify both ways still work.
        b.reopen();
        b.setPeer(new InetSocketAddress("127.0.0.1", 50111));
        if (!b.send(out, out.length)) throw new AssertionError("send b");
        p = a.receive(new byte[64]);
        if (p.getLength() != out.length) throw new AssertionError("len2");
        a.close();
        b.close();
        System.out.println("UdpSocketSelfTest OK");
    }
}
