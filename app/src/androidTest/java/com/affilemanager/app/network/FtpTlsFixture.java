package com.affilemanager.app.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;

/** Bounded, loopback-only explicit FTPS fixture; private keys never leave memory. */
public final class FtpTlsFixture {
    public static final class Material implements Closeable {
        public final SSLContext context;
        public final KeyStore trustStore;
        private final KeyPair pair;
        public Material() throws Exception {
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);pair=generator.generateKeyPair();
            byte[] algorithm=hex("300d06092a864886f70d01010b0500"), subject=new X500Principal("CN=localhost").getEncoded();
            long now=System.currentTimeMillis();SimpleDateFormat utc=new SimpleDateFormat("yyMMddHHmmss'Z'",Locale.US);utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            byte[] validity=der(0x30,der(0x17,utc.format(new Date(now-60000)).getBytes(StandardCharsets.US_ASCII)),der(0x17,utc.format(new Date(now+86400000)).getBytes(StandardCharsets.US_ASCII)));
            byte[] names=der(0x30,der(0x82,"localhost".getBytes(StandardCharsets.US_ASCII)),der(0x87,new byte[]{127,0,0,1}));
            byte[] extensions=der(0xa3,der(0x30,der(0x30,hex("0603551d11"),der(0x04,names))));
            byte[] body=der(0x30,der(0xa0,der(0x02,new byte[]{2})),der(0x02,new byte[]{1}),algorithm,subject,validity,subject,pair.getPublic().getEncoded(),extensions);
            Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(pair.getPrivate());signer.update(body);
            byte[] encoded=der(0x30,body,algorithm,der(0x03,new byte[]{0},signer.sign()));
            X509Certificate certificate=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
            certificate.checkValidity();certificate.verify(pair.getPublic());
            trustStore=KeyStore.getInstance(KeyStore.getDefaultType());trustStore.load(null);trustStore.setCertificateEntry("fixture",certificate);
            X509KeyManager manager=new X509KeyManager(){
                public String[] getClientAliases(String key,Principal[] issuers){return null;}
                public String chooseClientAlias(String[] keys,Principal[] issuers,Socket socket){return null;}
                public String[] getServerAliases(String key,Principal[] issuers){return "RSA".equals(key)?new String[]{"fixture"}:null;}
                public String chooseServerAlias(String key,Principal[] issuers,Socket socket){return "RSA".equals(key)?"fixture":null;}
                public X509Certificate[] getCertificateChain(String alias){return "fixture".equals(alias)?new X509Certificate[]{certificate}:null;}
                public PrivateKey getPrivateKey(String alias){return "fixture".equals(alias)?pair.getPrivate():null;}
            };
            context=SSLContext.getInstance("TLS");context.init(new KeyManager[]{manager},null,null);
        }
        public void close(){try{pair.getPrivate().destroy();}catch(javax.security.auth.DestroyFailedException ignored){}}
        private static byte[] hex(String value){byte[] bytes=new byte[value.length()/2];for(int i=0;i<bytes.length;i++)bytes[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return bytes;}
        private static byte[] der(int tag,byte[]...chunks)throws IOException{
            int size=0;for(byte[] chunk:chunks)size=Math.addExact(size,chunk.length);if(size>8192)throw new IOException("Certificate fixture exceeded its bound");
            ByteArrayOutputStream out=new ByteArrayOutputStream(size+4);out.write(tag);
            if(size<128)out.write(size);else if(size<=255){out.write(0x81);out.write(size);}else{out.write(0x82);out.write(size>>>8);out.write(size&255);}
            for(byte[] chunk:chunks)out.write(chunk);return out.toByteArray();
        }
    }
    public static final class Endpoint implements Closeable {
        private final SSLContext tls;
        private final ServerSocket server;
        private final AtomicReference<Socket> connection=new AtomicReference<>();
        public final AtomicBoolean loginReceived=new AtomicBoolean(false);
        public final AtomicReference<Throwable> failure=new AtomicReference<>();
        public final int port;
        private Thread worker;
        public Endpoint(SSLContext tls)throws IOException{this(tls,true);}
        public Endpoint(SSLContext tls,boolean startImmediately)throws IOException{
            this.tls=tls;server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));server.setSoTimeout(10000);port=server.getLocalPort();
            if(startImmediately)start();
        }
        public void start(){
            if(worker!=null)throw new IllegalStateException("Fixture already started");
            worker=new Thread(this::serve,"af-android-ftps");worker.setDaemon(true);worker.start();
        }
        private void serve(){
            try{
                Socket plain=server.accept();plain.setSoTimeout(5000);connection.set(plain);Socket active=plain;
                BufferedReader input=new BufferedReader(new InputStreamReader(active.getInputStream(),StandardCharsets.US_ASCII));
                BufferedWriter output=new BufferedWriter(new OutputStreamWriter(active.getOutputStream(),StandardCharsets.US_ASCII));
                reply(output,"220 AF owned loopback");
                for(int commandCount=0;commandCount<40;commandCount++){
                    String line=input.readLine();if(line==null)return;String command=line.split(" ",2)[0].toUpperCase(Locale.ROOT);
                    switch(command){
                        case "AUTH":
                            if(tls==null)throw new IOException("TLS not configured");reply(output,"234 Begin TLS");
                            SSLSocket secure=(SSLSocket)tls.getSocketFactory().createSocket(plain,"localhost",port,true);
                            secure.setUseClientMode(false);secure.setEnabledProtocols(new String[]{"TLSv1.2"});secure.setSoTimeout(5000);connection.set(secure);secure.startHandshake();active=secure;
                            input=new BufferedReader(new InputStreamReader(active.getInputStream(),StandardCharsets.US_ASCII));
                            output=new BufferedWriter(new OutputStreamWriter(active.getOutputStream(),StandardCharsets.US_ASCII));break;
                        case "USER":loginReceived.set(true);reply(output,"331 Password required");break;
                        case "PASS":reply(output,"230 Login accepted");break;
                        case "FEAT":reply(output,"211 No extensions");break;
                        case "SYST":reply(output,"215 UNIX Type: L8");break;
                        case "PBSZ":case "PROT":case "TYPE":case "NOOP":reply(output,"200 Accepted");break;
                        case "QUIT":reply(output,"221 Goodbye");return;
                        default:reply(output,"502 Not implemented");
                    }
                }
            }catch(IOException error){failure.set(error);}
            finally{Socket socket=connection.getAndSet(null);if(socket!=null)try{socket.close();}catch(IOException error){failure.compareAndSet(null,error);}}
        }
        private static void reply(BufferedWriter output,String text)throws IOException{output.write(text+"\r\n");output.flush();}
        public void close()throws IOException{
            Socket socket=connection.getAndSet(null);if(socket!=null)socket.close();server.close();
            if(worker!=null)try{worker.join(6000);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}
            if(worker!=null&&worker.isAlive())throw new IOException("Owned TLS fixture failed to stop");
        }
    }
}
