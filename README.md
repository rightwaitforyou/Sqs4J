按照张宴的"基于 HTTP GET/POST 协议的轻量级开源简单消息队列服务"协议用纯java实现.可以达到每秒并发10000-20000个请求.使用Netty做网络IO,LevelDB数据库来做数据的持久化存储.

队列（Queue）又称先进先出表（First In First Out），即先进入队列的元素，先从队列中取出。加入元素的一头叫“队头”，取出元素的一头叫“队尾”。利用消息队列可以很好地异步处理数据传送和存储，当你频繁地向数据库中插入数据、频繁地向搜索引擎提交数据，就可采取消息队列来异步插入。另外，还可以将较慢的处理逻辑、有并发数量限制的处理逻辑，通过消息队列放在后台处理，例如FLV视频转换、发送手机短信、发送电子邮件等。

#### HTTPSQS 具有以下特征：

+ [x] 非常简单，基于 HTTP GET/POST 协议。PHP、Java、Perl、Shell、Python、Ruby等支持HTTP协议的编程语言均可调用。
+ [x] 非常快速，入队列、出队列速度超过10000次/秒。
+ [x] 高并发，支持5K以上的并发连接。
+ [x] 支持多队列。
+ [x] 单个队列支持的最大队列数量高达10亿条。
+ [x] 低内存消耗，海量数据存储，存储几十GB的数据只需不到100MB的物理内存缓冲区。
+ [x] 可以在不停止服务的情况下便捷地修改单个队列的最大队列数量。
+ [x] 可以实时查看队列状态（入队列位置、出队列位置、未读队列数量、最大队列数量）。
+ [x] 可以查看指定队列ID（队列点）的内容，包括未出、已出的队列内容。
+ [x] 查看队列内容时，支持多字符集编码。
+ [x] 源代码不超过800行，适合二次开发。

--------------------------------------------------------------------------------
+ [x]说明可以参见张宴的Blog: http://blog.s135.com/httpsqs/
+ [x]最新下载地址: http://dl.bintray.com/wjw465150/Java/Sqs4J/sqs4j_LevelDB.tar.gz