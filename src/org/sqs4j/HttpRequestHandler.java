package org.sqs4j;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.QueryStringDecoder;

public class HttpRequestHandler extends SimpleChannelUpstreamHandler {
  //@wjw_comment 注意:因为使用了HttpServerPipelineFactory给每一个ChannelPipeline创建一个新的HttpRequestHandler实例(也就是说不是单实例的),
  //没有共享冲突,可以放心使用实例变量!!!
  private HttpRequest _request;
  private boolean _readingChunks;
  private final StringBuilder _buf = new StringBuilder(128);  //Buffer that stores the response content

  private final Sqs4jApp _app;
  private Charset _charsetObj;

  public HttpRequestHandler(Sqs4jApp app) {
    _app = app;
    _charsetObj = _app._conf.charsetDefaultCharset;
  }

  private boolean checkUser(HttpResponse response) throws IOException {
    String username = "";
    String password = "";
    String userPass = _request.getHeader("Authorization");

    if (null != userPass) {
      userPass = userPass.substring(6, userPass.length());

      userPass = _app.getBASE64DecodeOfStr(userPass, _charsetObj.name());
      final int pos = userPass.indexOf(':');
      if (pos > 0) {
        username = userPass.substring(0, pos);
        password = userPass.substring(pos + 1, userPass.length());
      }
    } else {
      response.setHeader("WWW-Authenticate", "Basic realm=\"Sqs4J\"");
      response.setStatus(HttpResponseStatus.UNAUTHORIZED);
      _buf.append("HTTPSQS_ERROR:需要用户名/口令!");
      return false;
    }

    if (_app._conf.adminUser.equals(username) && _app._conf.adminPass.equals(password)) {
      return true;
    } else {
      response.setHeader("WWW-Authenticate", "Basic realm=\"Sqs4J\"");
      response.setStatus(HttpResponseStatus.UNAUTHORIZED);
      _buf.append("HTTPSQS_ERROR:用户户名/口令 错误!");
      return false;
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
          throws Exception {
    //e.getCause().printStackTrace();
    e.getChannel().close();
  }

  /**
   * 处理模块
   *
   * @param ctx
   * @param e
   * @throws Exception
   */
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    if (!_readingChunks) {
      _request = (HttpRequest) e.getMessage();
      _buf.setLength(0);

      if (_request.isChunked()) {
        _readingChunks = true;
      } else {
        writeResponse(e);
      }
    } else {
      final HttpChunk chunk = (HttpChunk) e.getMessage();

      if (chunk.isLast()) {
        _readingChunks = false;
        writeResponse(e);
      }
    }

  }

  private void writeResponse(MessageEvent e) throws IOException {
    //分析URL参数
    QueryStringDecoder queryStringDecoder = new QueryStringDecoder(_request.getUri(), _app._conf.charsetDefaultCharset);
    Map<String, List<String>> requestParameters = queryStringDecoder.getParameters();

    String charset = (null != requestParameters.get("charset")) ? requestParameters.get("charset").get(0) : null;  //先从query里查找charset
    if (null == charset) {
      if (null != _request.getHeader("Content-Type")) {
        charset = _app.getCharsetFromContentType(_request.getHeader("Content-Type"));
        if (null == charset) {
          charset = _app._conf.defaultCharset;
        } else if (!charset.equalsIgnoreCase(_app._conf.defaultCharset)) {  //说明查询参数里指定了字符集,并且与缺省字符集不一致
          _charsetObj = Charset.forName(charset);
          queryStringDecoder = new QueryStringDecoder(_request.getUri(), _charsetObj);
          requestParameters = queryStringDecoder.getParameters();
        }
      } else {
        charset = _app._conf.defaultCharset;
      }
    } else if (!charset.equalsIgnoreCase(_app._conf.defaultCharset)) {  //说明查询参数里指定了字符集,并且与缺省字符集不一致
      _charsetObj = Charset.forName(charset);
      queryStringDecoder = new QueryStringDecoder(_request.getUri(), _charsetObj);
      requestParameters = queryStringDecoder.getParameters();
    }

    //接收GET表单参数
    final String httpsqs_input_auth = (null != requestParameters.get("auth")) ? requestParameters.get("auth").get(0) : null; /* get,put,view的验证密码 */
    final String httpsqs_input_name = (null != requestParameters.get("name")) ? requestParameters.get("name").get(0) : null; /* 队列名称 */
    final String httpsqs_input_opt = (null != requestParameters.get("opt")) ? requestParameters.get("opt").get(0) : null; //操作类别
    final String httpsqs_input_data = (null != requestParameters.get("data")) ? requestParameters.get("data").get(0) : null; //队列数据
    final String httpsqs_input_pos_tmp = (null != requestParameters.get("pos")) ? requestParameters.get("pos").get(0) : null; //队列位置点
    final String httpsqs_input_num_tmp = (null != requestParameters.get("num")) ? requestParameters.get("num").get(0) : null; //队列总长度
    long httpsqs_input_pos = 0;
    long httpsqs_input_num = 0;
    if (null != httpsqs_input_pos_tmp) {
      httpsqs_input_pos = Long.parseLong(httpsqs_input_pos_tmp);
    }
    if (null != httpsqs_input_num_tmp) {
      httpsqs_input_num = Long.parseLong(httpsqs_input_num_tmp);
    }

    //返回给用户的Header头信息
    final HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    response.setHeader("Content-Type", "text/plain;charset=" + charset);
    response.setHeader("Connection", "keep-alive");
    response.setHeader("Cache-Control", "no-cache");

    Sqs4jApp._lock.lock();
    try {
      /*参数是否存在判断 */
      if (null != httpsqs_input_name && null != httpsqs_input_opt && httpsqs_input_name.length() <= 256) {
        /* 入队列 */
        if (httpsqs_input_opt.equals("put")) {
          if (null != _app._conf.auth && !_app._conf.auth.equals(httpsqs_input_auth)) {
            _buf.append("HTTPSQS_AUTH_FAILED");
          } else {
            /* 优先接收POST正文信息 */
            if (_request.getMethod().getName().equalsIgnoreCase("POST")) {
              final long now_putpos = _app.httpsqs_now_putpos(httpsqs_input_name);
              if (now_putpos > 0) {
                final String key = httpsqs_input_name + ":" + now_putpos;
                final String value = URLDecoder.decode(_request.getContent().toString(_charsetObj), charset);
                _app._db.put(key.getBytes(Sqs4jApp.DB_CHARSET), value.getBytes(Sqs4jApp.DB_CHARSET));
                response.setHeader("Pos", now_putpos);
                _buf.append("HTTPSQS_PUT_OK");
              } else {
                _buf.append("HTTPSQS_PUT_END");
              }
            } else if (null != httpsqs_input_data) { //如果POST正文无内容，则取URL中data参数的值
              final long now_putpos = _app.httpsqs_now_putpos(httpsqs_input_name);
              if (now_putpos > 0) {
                final String key = httpsqs_input_name + ":" + now_putpos;
                final String value = httpsqs_input_data;
                _app._db.put(key.getBytes(Sqs4jApp.DB_CHARSET), value.getBytes(Sqs4jApp.DB_CHARSET));
                response.setHeader("Pos", now_putpos);
                _buf.append("HTTPSQS_PUT_OK");
              } else {
                _buf.append("HTTPSQS_PUT_END");
              }
            } else {
              _buf.append("HTTPSQS_PUT_ERROR");
            }
          }
        } else if (httpsqs_input_opt.equals("get")) { //出队列
          if (null != _app._conf.auth && !_app._conf.auth.equals(httpsqs_input_auth)) {
            _buf.append("HTTPSQS_AUTH_FAILED");
          } else {
            final long now_getpos = _app.httpsqs_now_getpos(httpsqs_input_name);
            if (0 == now_getpos) {
              _buf.append("HTTPSQS_GET_END");
            } else {
              final String key = httpsqs_input_name + ":" + now_getpos;
              final byte[] value = _app._db.get(key.getBytes(Sqs4jApp.DB_CHARSET));
              if (null != value) {
                response.setHeader("Pos", now_getpos);
                _buf.append(new String(value, Sqs4jApp.DB_CHARSET));
              } else {
                _buf.append("HTTPSQS_GET_END");
              }
            }
          }
          /* 查看单条队列内容 */
        } else if (httpsqs_input_opt.equals("view") && httpsqs_input_pos >= 1
            && httpsqs_input_pos <= Sqs4jApp.DEFAULT_MAXQUEUE) {
          if (null != _app._conf.auth && !_app._conf.auth.equals(httpsqs_input_auth)) {
            _buf.append("HTTPSQS_AUTH_FAILED");
          } else {
            final String httpsqs_output_value = _app.httpsqs_view(httpsqs_input_name, httpsqs_input_pos);
            if (httpsqs_output_value == null) {
              _buf.append("HTTPSQS_ERROR_NOFOUND");
            } else {
              _buf.append(httpsqs_output_value);
            }
          }
          /* 查看队列状态（普通浏览方式） */
        } else if (httpsqs_input_opt.equals("status")) {
          String put_times = "1st lap";
          final String get_times = "1st lap";

          final long maxqueue = _app.httpsqs_read_maxqueue(httpsqs_input_name); /* 最大队列数量 */
          final long putpos = _app.httpsqs_read_putpos(httpsqs_input_name);     /* 入队列写入位置 */
          final long getpos = _app.httpsqs_read_getpos(httpsqs_input_name);     /* 出队列读取位置 */
          long ungetnum = 0;
          if (putpos >= getpos) {
            ungetnum = Math.abs(putpos - getpos); //尚未出队列条数
          } else if (putpos < getpos) {
            ungetnum = Math.abs(maxqueue - getpos + putpos); /* 尚未出队列条数 */
            put_times = "2nd lap";
          }
          _buf.append(String.format("HTTP Simple Queue Service (Sqs4J)v%s\n", Sqs4jApp.VERSION));
          _buf.append("------------------------------\n");
          _buf.append(String.format("Queue Name: %s\n", httpsqs_input_name));
          _buf.append(String.format("Maximum number of queues: %d\n", maxqueue));
          _buf.append(String.format("Put position of queue (%s): %d\n", put_times, putpos));
          _buf.append(String.format("Get position of queue (%s): %d\n", get_times, getpos));
          _buf.append(String.format("Number of unread queue: %d\n", ungetnum));
          _buf.append("ScheduleSync running: " + !_app._scheduleSync.isShutdown());
          /* 查看队列状态（JSON方式，方便客服端程序处理） */
        } else if (httpsqs_input_opt.equals("status_json")) {
          String put_times = "1st lap";
          final String get_times = "1st lap";

          final long maxqueue = _app.httpsqs_read_maxqueue(httpsqs_input_name); /* 最大队列数量 */
          final long putpos = _app.httpsqs_read_putpos(httpsqs_input_name);     /* 入队列写入位置 */
          final long getpos = _app.httpsqs_read_getpos(httpsqs_input_name);     /* 出队列读取位置 */
          long ungetnum = 0;
          if (putpos >= getpos) {
            ungetnum = Math.abs(putpos - getpos); //尚未出队列条数
          } else if (putpos < getpos) {
            ungetnum = Math.abs(maxqueue - getpos + putpos); /* 尚未出队列条数 */
            put_times = "2nd lap";
          }

          _buf.append(String.format("{\"name\": \"%s\",\"maxqueue\": %d,\"putpos\": %d,\"putlap\": \"%s\",\"getpos\": %d,\"getlap\": \"%s\",\"unread\": %d, \"sync\": \"%s\"}\n", httpsqs_input_name, maxqueue, putpos, put_times, getpos, get_times, ungetnum, !_app._scheduleSync.isShutdown()));
          /* 重置队列 */
        } else if (httpsqs_input_opt.equals("reset")) {
          if (checkUser(response)) {
            final boolean reset = _app.httpsqs_reset(httpsqs_input_name);
            if (reset) {
              _buf.append(String.format("%s", "HTTPSQS_RESET_OK"));
            } else {
              _buf.append(String.format("%s", "HTTPSQS_RESET_ERROR"));
            }
          }
          /* 设置最大的队列数量，最小值为10条，最大值为10亿条 */
        } else if (httpsqs_input_opt.equals("maxqueue") && httpsqs_input_num >= 10
            && httpsqs_input_num <= Sqs4jApp.DEFAULT_MAXQUEUE) {
          if (checkUser(response)) {
            if (_app.httpsqs_maxqueue(httpsqs_input_name, httpsqs_input_num) != 0) {
              _buf.append(String.format("%s", "HTTPSQS_MAXQUEUE_OK")); //设置成功
            } else {
              _buf.append(String.format("%s", "HTTPSQS_MAXQUEUE_CANCEL")); //设置取消
            }
          }
          /* 设置定时更新内存内容到磁盘的间隔时间，最小值为1秒，最大值为10亿秒 */
        } else if (httpsqs_input_opt.equals("synctime") && httpsqs_input_num >= 1
            && httpsqs_input_num <= Sqs4jApp.DEFAULT_MAXQUEUE) {
          if (checkUser(response)) {
            if (_app.httpsqs_synctime((int) httpsqs_input_num) >= 1) {
              _buf.append(String.format("%s", "HTTPSQS_SYNCTIME_OK"));
            } else {
              _buf.append(String.format("%s", "HTTPSQS_SYNCTIME_CANCEL"));
            }
          }
          /* 手动刷新内存内容到磁盘 */
        } else if (httpsqs_input_opt.equals("flush")) {
          if (checkUser(response)) {
            _app.flush();
            _buf.append(String.format("%s", "HTTPSQS_FLUSH_OK"));
          }
        } else { /* 命令错误 */
          _buf.append(String.format("%s", "HTTPSQS_ERROR:未知的命令!"));
        }
      } else {
        _buf.append(String.format("%s", "HTTPSQS_ERROR:队列名错误!"));
      }
    } catch (Throwable ex) {
      ex.printStackTrace();
    } finally {
      Sqs4jApp._lock.unlock();
    }

    response.setContent(ChannelBuffers.copiedBuffer(_buf.toString(), _charsetObj));
    response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, response.getContent().readableBytes());
    // Write the response.
    ChannelFuture future = e.getChannel().write(response);

    // Close the non-keep-alive connection after the write operation is done.
    boolean keepAlive = HttpHeaders.isKeepAlive(_request);
    if (!keepAlive) {
      future.addListener(ChannelFutureListener.CLOSE);
    }

  }

}
