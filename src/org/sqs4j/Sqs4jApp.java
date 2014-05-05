package org.sqs4j;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.MBeanServer;
import javax.management.remote.JMXAuthenticator;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXPrincipal;
import javax.management.remote.JMXServiceURL;
import javax.security.auth.Subject;

import org.apache.log4j.PropertyConfigurator;
import org.apache.log4j.xml.DOMConfigurator;
import org.fusesource.leveldbjni.JniDBFactory;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.tanukisoftware.wrapper.WrapperManager;

/**
 * ����HTTPЭ�����������Դ�򵥶��з���. User: wstone Date: 2010-7-30 Time: 11:44:52
 */
public class Sqs4jApp implements Runnable {
	public static final String VERSION = "4.0.14"; //��ǰ�汾
	static final String DB_CHARSET = "UTF-8"; //���ݿ��ַ���
	static final long DEFAULT_MAXQUEUE = 1000000000; //ȱʡ�����������10����
	static final String KEY_PUTPOS = ":putpos";
	static final String KEY_GETPOS = ":getpos";
	static final String KEY_MAXQUEUE = ":maxqueue";

	private final String CONF_NAME; //�����ļ�

	org.slf4j.Logger _log = org.slf4j.LoggerFactory.getLogger(this.getClass());
	Sqs4jConf _conf; //�����ļ�

	private boolean _rmiCreated;
	private Registry _rmiRegistry; //RIM ע���
	private JMXConnectorServer _jmxCS; //JMXConnectorServer

	static Lock _lock = new ReentrantLock(); //HTTP���󲢷���
	public DB _db; //���ݿ�
	public DB _meta; //META���ݿ�,���������

	//ͬ�����̵�Scheduled
	public ScheduledExecutorService _scheduleSync = Executors.newSingleThreadScheduledExecutor();

	private io.netty.channel.Channel _channel; //Socketͨ��
	private EventLoopGroup _bossGroup;
	private EventLoopGroup _workerGroup;

	//��ʼ��Ŀ¼��Log4j
	static {
		try {
			File file = new File(System.getProperty("user.dir", ".") + "/conf/");
			if (!file.exists() && !file.mkdirs()) {
				throw new IOException("Can not create:" + System.getProperty("user.dir", ".") + "/conf/");
			}

			file = new File(System.getProperty("user.dir", ".") + "/db/");
			if (!file.exists() && !file.mkdirs()) {
				throw new IOException("Can not create:" + System.getProperty("user.dir", ".") + "/db/");
			}
			file = new File(System.getProperty("user.dir", ".") + "/db/META");
			if (!file.exists() && !file.mkdirs()) {
				throw new IOException("Can not create:" + System.getProperty("user.dir", ".") + "/db/META");
			}

			final String logPath = System.getProperty("user.dir", ".") + "/conf/log4j.xml";
			if (logPath.toLowerCase().endsWith(".xml")) {
				DOMConfigurator.configure(logPath);
			} else {
				PropertyConfigurator.configure(logPath);
			}
		} catch (Throwable e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	public static void main(String args[]) {
		@SuppressWarnings("unused")
		final Sqs4jApp app = new Sqs4jApp(args);
	}

	@Override
	//��ʱ���ڴ��е�����д�����
	public void run() {
		this.flush();
	}

	/**
	 * ���캯��
	 * 
	 * @param args
	 */
	public Sqs4jApp(String args[]) {
		java.lang.Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			public void run() {
				doStop();
			}
		}));

		CONF_NAME = System.getProperty("user.dir", ".") + "/conf/Sqs4jConf.xml";

		if (!this.doStart()) {
			System.exit(-1);
		}

	}

	String getBASE64DecodeOfStr(String inStr, String charset) throws UnsupportedEncodingException, IOException {
		return new String(Base64.decode(inStr), charset);
	}

	/**
	 * ��HTTP Header���ҵ��ַ�������,û�з��ַ���null
	 * 
	 * @param contentType
	 * @return
	 */
	String getCharsetFromContentType(String contentType) {
		if (null == contentType) {
			return null;
		}
		final int start = contentType.indexOf("charset=");
		if (start < 0) {
			return null;
		}
		String encoding = contentType.substring(start + 8);
		final int end = encoding.indexOf(';');
		if (end >= 0) {
			encoding = encoding.substring(0, end);
		}
		encoding = encoding.trim();
		if ((encoding.length() > 2) && ('"' == encoding.charAt(0)) && (encoding.endsWith("\""))) {
			encoding = encoding.substring(1, encoding.length() - 1);
		}
		return (encoding.trim());
	}

	/**
	 * ��HTTP��URL������������ҵ��ַ�������,û�з��ַ���null
	 * 
	 * @param query
	 * @return
	 */
	String getCharsetFromQuery(String query) {
		if (null == query) {
			return null;
		}
		final int start = query.indexOf("charset=");
		if (start < 0) {
			return null;
		}
		String encoding = query.substring(start + 8);
		final int end = encoding.indexOf('&');
		if (end >= 0) {
			encoding = encoding.substring(0, end);
		}
		encoding = encoding.trim();
		return encoding;
	}

	/**
	 * ��HTTP��URL���������������Map
	 * 
	 * @param query
	 * @param charset
	 * @return
	 */
	private Map<String, String> makeParameters(String query, String charset) {
		final Map<String, String> map = new HashMap<String, String>();
		if (null == query || null == charset) {
			return map;
		}

		final String[] keyValues;
		keyValues = query.split("&");
		for (String keyValue : keyValues) {
			String[] kv = keyValue.split("=");
			if (2 == kv.length) {
				try {
					map.put(kv[0], URLDecoder.decode(kv[1], charset));
				} catch (UnsupportedEncodingException e) {
					//
				}
			}
		}

		return map;
	}

	public void flush() {
		//level jni no opt!
	}

	/* ��ȡ����д����ֵ */

	public long httpsqs_read_putpos(String httpsqs_input_name) throws UnsupportedEncodingException {
		final String key = httpsqs_input_name + KEY_PUTPOS;
		final byte[] value = _db.get(key.getBytes(DB_CHARSET));
		if (null == value) {
			return 0;
		} else {
			return Long.parseLong(new String(value, DB_CHARSET));
		}
	}

	/* ��ȡ���ж�ȡ���ֵ */

	public long httpsqs_read_getpos(String httpsqs_input_name) throws UnsupportedEncodingException {
		final String key = httpsqs_input_name + KEY_GETPOS;
		final byte[] value = _db.get(key.getBytes(DB_CHARSET));
		if (null == value) {
			return 0;
		} else {
			return Long.parseLong(new String(value, DB_CHARSET));
		}
	}

	/* ��ȡ�������õ��������� */

	public long httpsqs_read_maxqueue(String httpsqs_input_name) throws UnsupportedEncodingException {
		final String key = httpsqs_input_name + KEY_MAXQUEUE;
		final byte[] value = _db.get(key.getBytes(DB_CHARSET));
		if (null == value) {
			return DEFAULT_MAXQUEUE;
		} else {
			return Long.parseLong(new String(value, DB_CHARSET));
		}
	}

	/**
	 * �������Ķ�������������ֵΪ���õĶ����������������ֵΪ0�����ʾ����ȡ����ȡ��ԭ��Ϊ��
	 * ���õ����Ķ�������С�ڡ���ǰ����д��λ�õ㡰�͡���ǰ���ж�ȡλ�õ㡰�����ߡ���ǰ����д��λ�õ㡰С�ڡ���ǰ���еĶ�ȡλ�õ㣩
	 * 
	 * @param httpsqs_input_name
	 * @param httpsqs_input_num
	 * @return
	 */
	long httpsqs_maxqueue(String httpsqs_input_name, long httpsqs_input_num) throws UnsupportedEncodingException {
		final long queue_put_value = httpsqs_read_putpos(httpsqs_input_name);
		final long queue_get_value = httpsqs_read_getpos(httpsqs_input_name);

		if (queue_put_value == 0 && queue_get_value == 0) { //���иմ���
			addQueueName(httpsqs_input_name);
		}
		
		/* ���õ����Ķ�������������ڵ��ڡ���ǰ����д��λ�õ㡰�͡���ǰ���ж�ȡλ�õ㡰�����ҡ���ǰ����д��λ�õ㡰������ڵ��ڡ���ǰ���ж�ȡλ�õ㡰 */
		if (httpsqs_input_num >= queue_put_value && httpsqs_input_num >= queue_get_value && queue_put_value >= queue_get_value) {
			final String key = httpsqs_input_name + KEY_MAXQUEUE;
			_db.put(key.getBytes(DB_CHARSET), String.valueOf(httpsqs_input_num).getBytes(DB_CHARSET));

			this.flush(); //ʵʱˢ�µ�����
			_log.warn(String.format("�������ñ��޸�:(%s:maxqueue)=%d", httpsqs_input_name, httpsqs_input_num));
			return httpsqs_input_num;
		} else {
			return 0L;
		}
	}

	/**
	 * ���ö��У�true��ʾ���óɹ�
	 * 
	 * @param httpsqs_input_name
	 * @return
	 */
	boolean httpsqs_reset(String httpsqs_input_name) throws UnsupportedEncodingException {
		long queue_put_value = httpsqs_read_putpos(httpsqs_input_name);
		final long queue_get_value = httpsqs_read_getpos(httpsqs_input_name);
		if (queue_put_value == 0 && queue_get_value == 0) { //���иմ���
			addQueueName(httpsqs_input_name);
		}
		
		_db.put((httpsqs_input_name + KEY_PUTPOS).getBytes(DB_CHARSET), "0".getBytes(DB_CHARSET));
		_db.put((httpsqs_input_name + KEY_GETPOS).getBytes(DB_CHARSET), "0".getBytes(DB_CHARSET));
		_db.put((httpsqs_input_name + KEY_MAXQUEUE).getBytes(DB_CHARSET), String.valueOf(DEFAULT_MAXQUEUE).getBytes(DB_CHARSET));

		this.flush(); //ʵʱˢ�µ�����
		_log.warn(String.format("���б�����:(%s:maxqueue)", httpsqs_input_name));

		return true;
	}

	/**
	 * �鿴������������
	 * 
	 * @param httpsqs_input_name
	 * @param pos
	 * @return
	 */
	String httpsqs_view(String httpsqs_input_name, long pos) throws UnsupportedEncodingException {
		final String key = httpsqs_input_name + ":" + pos;
		final byte[] value = _db.get(key.getBytes(DB_CHARSET));
		if (null == value) {
			return null;
		} else {
			return new String(value, DB_CHARSET);
		}
	}

	/**
	 * �޸Ķ�ʱ�����ڴ����ݵ����̵ļ��ʱ�䣬���ؼ��ʱ�䣨�룩
	 * 
	 * @param httpsqs_input_num
	 * @return
	 */
	int httpsqs_synctime(int httpsqs_input_num) {
		if (httpsqs_input_num >= 1) {
			_conf.syncinterval = httpsqs_input_num;
			try {
				_conf.store(CONF_NAME);
				_scheduleSync.shutdown();

				_scheduleSync = Executors.newSingleThreadScheduledExecutor();
				_scheduleSync.scheduleWithFixedDelay(this, 1, _conf.syncinterval, TimeUnit.SECONDS);
				_log.info("�����ļ����޸�:" + _conf.toString());
			} catch (Exception ex) {
				_log.error(ex.getMessage(), ex);
			}
		}

		return _conf.syncinterval;
	}

	/**
	 * ��ȡ���Ρ�����С������Ķ���д��㣬����ֵΪ0ʱ���������ܾ�����д��
	 * 
	 * @param httpsqs_input_name
	 * @return
	 */
	long httpsqs_now_putpos(String httpsqs_input_name) throws UnsupportedEncodingException {
		final long maxqueue_num = httpsqs_read_maxqueue(httpsqs_input_name);
		long queue_put_value = httpsqs_read_putpos(httpsqs_input_name);
		final long queue_get_value = httpsqs_read_getpos(httpsqs_input_name);
		if (queue_put_value == 0 && queue_get_value == 0) { //���иմ���
			addQueueName(httpsqs_input_name);
		}

		final String key = httpsqs_input_name + KEY_PUTPOS;
		/* ����д��λ�õ��1 */
		queue_put_value = queue_put_value + 1;
		if (queue_put_value > maxqueue_num && queue_get_value <= 1) { // �������д��ID�������������������Ҵ�δ���й������в�����=0������й�1�γ����в�����=1��������0���ܾ�����д��
			queue_put_value = 0;
		} else if (queue_put_value == queue_get_value) { /*
																											 * �������д��ID+1֮��׷�϶��ж�ȡID��
																											 * ��˵���������� ������0���ܾ�����д��
																											 */
			queue_put_value = 0;
		} else if (queue_put_value > maxqueue_num) { /*
																									 * �������д��ID���������������������ö���д��λ�õ��ֵΪ1
																									 */
			_db.put(key.getBytes(DB_CHARSET), "1".getBytes(DB_CHARSET));
		} else { /* ����д��λ�õ��1���ֵ����д�����ݿ� */
			final String value = String.valueOf(queue_put_value);
			_db.put(key.getBytes(DB_CHARSET), value.getBytes(DB_CHARSET));
		}

		return queue_put_value;
	}

	/**
	 * ��ȡ���Ρ������С������Ķ��ж�ȡ�㣬����ֵΪ0ʱ����ȫ����ȡ���
	 * 
	 * @param httpsqs_input_name
	 * @return
	 */
	long httpsqs_now_getpos(String httpsqs_input_name) throws UnsupportedEncodingException {
		final long maxqueue_num = httpsqs_read_maxqueue(httpsqs_input_name);
		final long queue_put_value = httpsqs_read_putpos(httpsqs_input_name);
		long queue_get_value = httpsqs_read_getpos(httpsqs_input_name);

		final String key = httpsqs_input_name + KEY_GETPOS;
		/* ���queue_get_value��ֵ�����ڣ�����Ϊ1 */
		if (0 == queue_get_value && queue_put_value > 0) {
			queue_get_value = 1;
			final String value = String.valueOf(queue_get_value);
			_db.put(key.getBytes(DB_CHARSET), value.getBytes(DB_CHARSET));

			/* ������еĶ�ȡֵ�������У�С�ڶ��е�д��ֵ������У� */
		} else if (queue_get_value < queue_put_value) {
			queue_get_value = queue_get_value + 1;
			final String value = String.valueOf(queue_get_value);
			_db.put(key.getBytes(DB_CHARSET), value.getBytes(DB_CHARSET));

			/* ������еĶ�ȡֵ�������У����ڶ��е�д��ֵ������У������Ҷ��еĶ�ȡֵ�������У�С������������ */
		} else if (queue_get_value > queue_put_value && queue_get_value < maxqueue_num) {
			queue_get_value = queue_get_value + 1;
			final String value = String.valueOf(queue_get_value);
			_db.put(key.getBytes(DB_CHARSET), value.getBytes(DB_CHARSET));

			/* ������еĶ�ȡֵ�������У����ڶ��е�д��ֵ������У������Ҷ��еĶ�ȡֵ�������У��������������� */
		} else if (queue_get_value > queue_put_value && queue_get_value == maxqueue_num) {
			queue_get_value = 1;
			final String value = String.valueOf(queue_get_value);
			_db.put(key.getBytes(DB_CHARSET), value.getBytes(DB_CHARSET));

			/* ���еĶ�ȡֵ�������У����ڶ��е�д��ֵ������У����������е�������ȫ������ */
		} else {
			queue_get_value = 0;
		}

		return queue_get_value;
	}

	//�Ѹմ����Ķ������������!
	private void addQueueName(String name) {
		_meta.put(name.getBytes(), name.getBytes());
	}

	//��ȡȫ��������
	public List<String> getQueueNames() {
		List<String> llst = new ArrayList<String>();
		DBIterator dbIterator = _meta.iterator();
		for (dbIterator.seekToFirst(); dbIterator.hasNext(); dbIterator.next()) {
			byte[] bbQueuename = dbIterator.peekNext().getKey();
			llst.add(new String(bbQueuename));
		}

		return llst;
	}

	public boolean doStart() {
		try {
			try {
				_conf = Sqs4jConf.load(CONF_NAME);
			} catch (Exception ex) {
				//ex.printStackTrace();
				_conf = new Sqs4jConf();
				_conf.store(CONF_NAME);
			}
			if (null == _conf.dbPath || 0 == _conf.dbPath.length()) {
				_conf.dbPath = System.getProperty("user.dir", ".") + "/db";
			}
			if (null != _conf.auth && 0 == _conf.auth.trim().length()) {
				_conf.auth = null;
			}

			if (null == _db) {
				final org.iq80.leveldb.Logger logger = new org.iq80.leveldb.Logger() {
					public void log(String message) {
						_log.info(message);
					}
				};

				final Options options = new Options().createIfMissing(true);
				options.logger(logger);
				/*
				 * LevelDB��sst�ļ���СĬ����2M����������ʱ��������ֻ��Ҫ��options.write_buffer_size���
				 * ����options.write_buffer_size = 100000000������һ����sst����32M��
				 */
				options.writeBufferSize(256 * 1024 * 1024); //log��С���256M�����������л���־�Ŀ����ͼ������ݺϲ���Ƶ�ʡ�
				options.blockSize(256 * 1024); //256KB Block Size 
				options.cacheSize(100 * 1024 * 1024); // 100MB cache
				options.compressionType(CompressionType.SNAPPY);

				_db = JniDBFactory.factory.open(new File(_conf.dbPath), options);
			}
			if (null == _meta) {
				final Options options = new Options().createIfMissing(true);
				_meta = JniDBFactory.factory.open(new File(_conf.dbPath + "/META"), options);
			}

			_scheduleSync.scheduleWithFixedDelay(this, 1, _conf.syncinterval, TimeUnit.SECONDS);

			if (null == _channel) {
				InetSocketAddress addr;
				if (_conf.bindAddress.equals("*")) {
					addr = new InetSocketAddress(_conf.bindPort);
				} else {
					addr = new InetSocketAddress(_conf.bindAddress, _conf.bindPort);
				}

				_bossGroup = new NioEventLoopGroup();
				_workerGroup = new NioEventLoopGroup();
				io.netty.bootstrap.ServerBootstrap server = new io.netty.bootstrap.ServerBootstrap();
				server.group(_bossGroup, _workerGroup)
				    .channel(NioServerSocketChannel.class)
				    .option(ChannelOption.TCP_NODELAY, true)
				    .option(ChannelOption.SO_REUSEADDR, true)
				    .option(ChannelOption.SO_TIMEOUT, _conf.soTimeout * 1000)
				    .option(ChannelOption.SO_BACKLOG, _conf.backlog)
				    .childOption(ChannelOption.TCP_NODELAY, true)
				    .childOption(ChannelOption.SO_REUSEADDR, true)
				    .childOption(ChannelOption.SO_KEEPALIVE, true)
				    .childHandler(new HttpServerChannelInitializer(this));

				_channel = server.bind(addr).sync().channel();

				_log.info(String.format("Sqs4J Server is listening on Address:%s Port:%d\n%s",
				    _conf.bindAddress,
				    _conf.bindPort,
				    _conf.toString()));
			}

			if (_jmxCS == null) {
				final Map<String, Object> env = new HashMap<String, Object>();
				env.put(JMXConnectorServer.AUTHENTICATOR, new JMXAuthenticator() {
					public Subject authenticate(Object credentials) {
						final String[] sCredentials = (String[]) credentials;
						final String userName = sCredentials[0];
						final String password = sCredentials[1];
						if (_conf.adminUser.equals(userName) && _conf.adminPass.equals(password)) {
							final Set<Principal> principals = new HashSet<Principal>();
							principals.add(new JMXPrincipal(userName));
							return new Subject(true, principals, Collections.EMPTY_SET, Collections.EMPTY_SET);
						}

						throw new SecurityException("Authentication failed! ");
					}
				});

				final String localHostname = InetAddress.getLocalHost().getHostName();
				LocateRegistry.createRegistry(_conf.jmxPort);
				_log.info("Getting the platform's MBean Server");
				MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

				JMXServiceURL localUrl = new JMXServiceURL("service:jmx:rmi://" + localHostname + ":" + _conf.jmxPort + "/jndi/rmi://" + localHostname + ":" + _conf.jmxPort + "/jmxrmi");
				JMXServiceURL hostUrl = new JMXServiceURL("service:jmx:rmi://" + "127.0.0.1" + ":" + _conf.jmxPort + "/jndi/rmi://" + "127.0.0.1" + ":" + _conf.jmxPort + "/jmxrmi");
				_log.info("InetAddress.getLocalHost().getHostName() Connection URL: " + localUrl);
				_log.info("Used host Connection URL: " + hostUrl);

				_log.info("Creating RMI connector server");
				_jmxCS = JMXConnectorServerFactory.newJMXConnectorServer(hostUrl, env, mbs);
				_jmxCS.start();
				registerMBean(new org.sqs4j.jmx.Sqs4J(this), "org.sqs4j:type=Sqs4J");
			}

			if (!WrapperManager.isControlledByNativeWrapper()) {
				System.out.println("Started Standalone Sqs4J!");
			}

			return true;
		} catch (Throwable ex) {
			_log.error(ex.getMessage(), ex);
			return false;
		}
	}

	public boolean doStop() {
		_scheduleSync.shutdown();

		if (_channel != null) {
			try {
				_log.info("Now stoping Sqs4J Server ......");

				final ChannelFuture channelFuture = _channel.close();
				channelFuture.awaitUninterruptibly();
			} catch (Throwable ex) {
				_log.error(ex.getMessage(), ex);
			} finally {
				_channel = null;
				_log.info("Sqs4J Server is stoped!");
			}

			try {
				_bossGroup.shutdownGracefully();
			} catch (Throwable ex) {
				_log.error(ex.getMessage(), ex);
			}
			try {
				_workerGroup.shutdownGracefully();
			} catch (Throwable ex) {
				_log.error(ex.getMessage(), ex);
			}
		}

		if (_db != null) {
			this.flush(); //ʵʱˢ�µ�����

			try {
				_db.close();
			} catch (Throwable ex) {
				_log.error(ex.getMessage(), ex);
			} finally {
				_db = null;
			}
		}
		if (_meta != null) {
			this.flush(); //ʵʱˢ�µ�����

			try {
				_meta.close();
			} catch (Throwable ex) {
				_log.error(ex.getMessage(), ex);
			} finally {
				_meta = null;
			}
		}

		if (_jmxCS != null) {
			try {
				_jmxCS.stop();
			} catch (Throwable ex) {
				_log.error(ex.getMessage(), ex);
			} finally {
				_jmxCS = null;
			}
		}

		if (_rmiCreated && _rmiRegistry != null) {
			try {
				UnicastRemoteObject.unexportObject(_rmiRegistry, true);
			} catch (Throwable ex) {
				_log.error(ex.getMessage(), ex);
			} finally {
				_rmiRegistry = null;
			}
		}

		if (!WrapperManager.isControlledByNativeWrapper()) {
			System.out.println("Stoped Standalone Sqs4J!");
		}
		return true;
	}

	/**
	 * Java 1.5 and above supports the ability to register the WrapperManager
	 * MBean internally.
	 */
	@SuppressWarnings("rawtypes")
	private void registerMBean(Object mbean, String name) {
		Class classManagementFactory;
		Class classMBeanServer;
		Class classObjectName;
		try {
			classManagementFactory = Class.forName("java.lang.management.ManagementFactory");
			classMBeanServer = Class.forName("javax.management.MBeanServer");
			classObjectName = Class.forName("javax.management.ObjectName");
		} catch (ClassNotFoundException e) {
			_log.error("Registering MBeans not supported by current JVM:" + name);
			return;
		}

		try {
			// This code uses reflection so it combiles on older JVMs.
			// The original code is as follows:
			// javax.management.MBeanServer mbs =
			//     java.lang.management.ManagementFactory.getPlatformMBeanServer();
			// javax.management.ObjectName oName = new javax.management.ObjectName( name );
			// mbs.registerMBean( mbean, oName );

			// The version of the above code using reflection follows.
			Method methodGetPlatformMBeanServer = classManagementFactory.getMethod("getPlatformMBeanServer", (Class[]) null);
			Constructor constructorObjectName = classObjectName.getConstructor(new Class[] { String.class });
			Method methodRegisterMBean = classMBeanServer.getMethod("registerMBean",
			    new Class[] { Object.class, classObjectName });
			Object mbs = methodGetPlatformMBeanServer.invoke(null, (Object[]) null);
			Object oName = constructorObjectName.newInstance(new Object[] { name });
			methodRegisterMBean.invoke(mbs, new Object[] { mbean, oName });

			_log.info("Registered MBean with Platform MBean Server:" + name);
		} catch (Throwable t) {
			if (t instanceof ClassNotFoundException) {
				_log.error("Using MBean requires at least a JVM version 1.5.");
			}
			_log.error("Unable to register the " + name + " MBean.");
			t.printStackTrace();
		}
	}

}
