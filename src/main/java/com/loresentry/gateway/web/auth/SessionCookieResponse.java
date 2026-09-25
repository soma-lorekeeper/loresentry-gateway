package com.loresentry.gateway.web.auth;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.*;
import java.time.Instant;

/** Computes remaining cookie lifetime when response headers are about to be sent. */
public final class SessionCookieResponse extends HttpServletResponseWrapper {
    private final AuthCookies cookies;
    private final String id;
    private final Instant expiresAt;
    private boolean renewed;
    private ServletOutputStream output;
    private PrintWriter writer;

    public SessionCookieResponse(HttpServletResponse response, AuthCookies cookies, String id, Instant expiresAt) {
        super(response); this.cookies=cookies; this.id=id; this.expiresAt=expiresAt;
        // Validate before the request reaches a domain service; do not emit headers yet.
        cookies.session(id,expiresAt);
    }
    public void finish() {
        if (!renewed && !isCommitted()) {
            cookies.setSession((HttpServletResponse)getResponse(),id,expiresAt);
            renewed=true;
        }
    }
    @Override public void setHeader(String name,String value) {
        super.setHeader(name,"Cache-Control".equalsIgnoreCase(name)?"no-store":value);
    }
    @Override public void addHeader(String name,String value) {
        if("Cache-Control".equalsIgnoreCase(name)) super.setHeader(name,"no-store");
        else super.addHeader(name,value);
    }
    @Override public void reset() { super.reset(); renewed=false; }
    @Override public void flushBuffer() throws IOException { finish(); super.flushBuffer(); }
    @Override public void sendError(int status) throws IOException { finish(); super.sendError(status); }
    @Override public void sendError(int status,String message) throws IOException { finish(); super.sendError(status,message); }
    @Override public void sendRedirect(String location) throws IOException { finish(); super.sendRedirect(location); }
    @Override public ServletOutputStream getOutputStream() throws IOException {
        if(writer!=null) throw new IllegalStateException("Writer already selected");
        if(output==null) {
            var delegate=super.getOutputStream();
            output=new ServletOutputStream() {
                @Override public boolean isReady(){return delegate.isReady();}
                @Override public void setWriteListener(WriteListener listener){delegate.setWriteListener(listener);}
                @Override public void write(int value)throws IOException{finish();delegate.write(value);}
                @Override public void write(byte[] bytes,int offset,int length)throws IOException{finish();delegate.write(bytes,offset,length);}
                @Override public void flush()throws IOException{finish();delegate.flush();}
                @Override public void close()throws IOException{finish();delegate.close();}
            };
        }
        return output;
    }
    @Override public PrintWriter getWriter() throws IOException {
        if(output!=null) throw new IllegalStateException("Output stream already selected");
        if(writer==null) {
            var delegate=super.getWriter();
            writer=new PrintWriter(new Writer() {
                @Override public void write(char[] chars,int offset,int length){finish();delegate.write(chars,offset,length);}
                @Override public void flush(){finish();delegate.flush();}
                @Override public void close(){finish();delegate.close();}
            });
        }
        return writer;
    }
}
