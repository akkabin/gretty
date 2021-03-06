/*
 * Copyright 2009-2010 MBTE Sweden AB.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mbte.gretty.httpserver

import org.jboss.netty.handler.codec.http.HttpResponseStatus

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.*

import static org.jboss.netty.handler.codec.http.HttpVersion.*

import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.buffer.ChannelBuffers
import org.jboss.netty.handler.codec.http.HttpHeaders
import org.jboss.netty.handler.codec.http.DefaultHttpResponse
import org.codehaus.groovy.reflection.ReflectionCache
import org.jboss.netty.channel.Channel
import org.jboss.netty.channel.ChannelFutureListener
import org.jboss.netty.handler.codec.http.HttpVersion
import org.jboss.netty.handler.stream.ChunkedStream
import org.jboss.netty.handler.codec.http.DefaultHttpChunk
import org.jboss.netty.handler.codec.http.DefaultHttpChunkTrailer
import java.lang.reflect.Modifier
import org.mbte.gretty.JacksonCategory
import org.jboss.netty.handler.codec.http.CookieEncoder
import groovypp.concurrent.BindLater
import groovypp.concurrent.CallLater
import java.util.concurrent.Executor
import org.mbte.gretty.httpserver.session.GrettySession
import org.jboss.netty.handler.codec.http.DefaultCookie
import org.jboss.netty.handler.codec.http.Cookie

@Typed
@Use(JacksonCategory)
class GrettyHttpResponse extends DefaultHttpResponse {

    Object responseBody

    String charset = "UTF-8"

    protected volatile int async

    private volatile Channel channel
    private boolean keepAlive
    protected GrettySession session

    Set<Cookie> cookies = []

    GrettyHttpResponse (Channel channel, boolean keepAlive) {
        super(HTTP_1_1, HttpResponseStatus.FORBIDDEN)
        this.channel = channel
        this.keepAlive = keepAlive
    }

    GrettyHttpResponse (HttpVersion version, HttpResponseStatus status) {
        super(version, status)
    }

    Integer getChannelId() {
        channel?.id
    }

    void write(Object obj) {
        channel.write(obj)
    }

    void complete() {
        if(session) {
            addCookie("JSESSIONID", session.id)
            session?.server?.sessionManager?.storeSession(session)
            session = null
        }

        def channel = this.channel

        if (!channel)
            return

        this.channel = null

        def writeFuture = channel.write(this)
        if (responseBody) {
            writeFuture = channel.write(responseBody)
        }

        if (!keepAlive || status.code >= 400) {
            writeFuture.addListener(ChannelFutureListener.CLOSE)
        }
    }

    void setContent(ChannelBuffer obj) {
        if (responseBody || content.readableBytes() > 0)
            throw new IllegalStateException("Body of http response already set")

        if (status == HttpResponseStatus.FORBIDDEN) {
            status = HttpResponseStatus.OK
        }

        if (!getHeader(CONTENT_LENGTH))
            setHeader(CONTENT_LENGTH, obj.readableBytes())
        super.setContent(obj)
    }

    void setResponseBody(Object obj) {
        if (responseBody || content.readableBytes() > 0)
            throw new IllegalStateException("Body of http response already set")

        if (status == HttpResponseStatus.FORBIDDEN) {
            status = HttpResponseStatus.OK
        }

        switch(obj) {
            case String:
                content = ChannelBuffers.copiedBuffer(obj, charset)
            break

            case File:
                setHeader(HttpHeaders.Names.CONTENT_LENGTH, obj.length())
                this.responseBody = obj
            break

            case InputStream:
                this.responseBody = new HttpChunkedStream(obj)
            break

            default:
                this.responseBody = obj
        }
    }

    void setContentType(String type) {
        setHeader(CONTENT_TYPE, type)
    }

    void setText(Object body) {
        setHeader(CONTENT_TYPE, "text/plain; charset=$charset")
        content = ChannelBuffers.copiedBuffer(body.toString(), charset)
    }

    void setHtml(Object body) {
        setHeader(CONTENT_TYPE, "text/html; charset=$charset")
        content = ChannelBuffers.copiedBuffer(body.toString(), charset)
    }

    void setJson(Object body) {
        setHeader(CONTENT_TYPE, "application/json; charset=$charset")
        content = ChannelBuffers.copiedBuffer(body instanceof String ? body.toString() : body.toJsonString(), charset)
    }

    void setXml(Object body) {
        setHeader(CONTENT_TYPE, "application/xml; charset=$charset")
        content = ChannelBuffers.copiedBuffer(body.toString(), charset)
    }

    void redirect(String where) {
        status = HttpResponseStatus.MOVED_PERMANENTLY
        setHeader HttpHeaders.Names.LOCATION, where
        setHeader HttpHeaders.Names.CONTENT_LENGTH, 0
    }

    String getContentText () {
        new String(content.array(), content.arrayOffset(), content.readableBytes())
    }

    void close() {
        channel?.close()
    }

    Cookie getCookie(String name) {
        if(cookies) {
            for(c in cookies) {
                if(c.name == name)
                    return c
            }
        }
    }

    void addCookie(Cookie cookie) {
        if(!cookies)
            cookies = []
        cookies << cookie
    }

    void addCookie(String name, String value) {
        addCookie(new DefaultCookie(name, value))
    }
}
