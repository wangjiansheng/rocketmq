## Apache RocketMQ [![Build Status](https://travis-ci.org/apache/rocketmq.svg?branch=master)](https://travis-ci.org/apache/rocketmq) [![Coverage Status](https://coveralls.io/repos/github/apache/rocketmq/badge.svg?branch=master)](https://coveralls.io/github/apache/rocketmq?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.apache.rocketmq/rocketmq-all/badge.svg)](http://search.maven.org/#search%7Cga%7C1%7Corg.apache.rocketmq)
[![GitHub release](https://img.shields.io/badge/release-download-orange.svg)](https://rocketmq.apache.org/dowloading/releases)
[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

**[Apache RocketMQ](https://rocketmq.apache.org) is a distributed messaging and streaming platform with low latency, high performance and reliability, trillion-level capacity and flexible scalability.**


----------

## Learn it & Contact us
* Mailing Lists: <https://rocketmq.apache.org/about/contact/>
* Home: <https://rocketmq.apache.org>
* Docs: <https://rocketmq.apache.org/docs/quick-start/>
* Issues: <https://github.com/apache/rocketmq/issues>
* Rips: <https://github.com/apache/rocketmq/wiki/RocketMQ-Improvement-Proposal>
* Ask: <https://stackoverflow.com/questions/tagged/rocketmq>
* Slack: <https://rocketmq-invite-automation.herokuapp.com/>
 

---------
## Apache RocketMQ Community
* [RocketMQ Community Projects](https://github.com/apache/rocketmq-externals)
----------
## Contributing
We always welcome new contributions, whether for trivial cleanups, [big new features](https://github.com/apache/rocketmq/wiki/RocketMQ-Improvement-Proposal) or other material rewards, more details see [here](http://rocketmq.apache.org/docs/how-to-contribute/).

----------
## License
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html) Copyright (C) Apache Software Foundation

##重要的类

#broker


#namesev


#store
DefaultMessageStore.java  数据落地相关操作
* load()  启动时候调用,加载落地的数据 
* start() 启动时候调用,启动一些线程   来自 BrokerController.start();
* recoverTopicQueueTable() 恢复topic信息

ScheduleMessageService.java 延迟消息服务

* start() 延时消息处理（DeliverDelayedMessageTimerTask）   DefaultMessageStore.handleScheduleMessageService()中调用


MessageAccessor.java  消息寄存器 工具类 
* Message里面维护一个map  Map<String, String> properties


#client
##### DefaultMQPushConsumerImpl
* 发送请求 pullMessage()  pullCallback() pullKernelImpl()

#### MQClientAPIImpl 
* 发送请求 sendMessage()  
* sendMessageAsync()调用回调 pullCallback的方法


##### PullAPIWrapper.java  
* PullAPIWrapper：长连接，负责从broker处拉取消息，然后利用ConsumeMessageService回调用户的Listener执行消息消费逻辑
* pullKernelImpl()  发送请求的地方

##### PullMessageService.java  拉请求的服务（服务启动之后，会一直不停的循环调用拉取数据）
* 每个MessageQueue 对应了封装成了一个PullRequest，因为拉取数据是以每个Broker下面的Queue为单位，同时里面还一个ProcessQueue，每个MessageQueue也同样对应一个ProcessQueue，保存了这个MessageQueue消息处理状态的快照；还有nextOffset用来标识读取的位置
    
##### PullCallback回调
*  服务端处理完之后，给客户端响应，回调其中的PullCallback，其中在处理完消息之后，重要的一步就是再次把pullRequest放到PullMessageService服务中，等待下一次的轮询；


##### MQClientAPIImpl.java   客户端发送请求的地方（调用NettyRemotingClient）
*  这里面调用PullCallback回调


#####ConsumeMessageService
* 实现所谓的"Push-被动"消费机制；从Broker拉取的消息后，封装成ConsumeRequest提交给ConsumeMessageSerivce，此service负责回调用户的Listener消费消息；
#### ConsumeMessageConcurrentlyService
* ConsumeMessageConcurrentlyService.this.resetRetryTopic 当消息为重试消息，设置Topic为原始Topic
* 接收消息，并调用MessageListener.

#####RebalanceService 
* 均衡消息队列服务，负责分配当前 Consumer 可消费的消息队列( MessageQueue )。当有新的 Consumer 的加入或移除，都会重新分配消息队列。
* 三种情况情况下触发 见类的注释

#### RebalancePushImpl
* PushConsumer 消费进度读取 RebalancePushImpl#computePullFromWhere


#### PullMessageService
* 拉取消息服务，不断不断不断从 Broker 拉取消息，并提交消费任务到 ConsumeMessageService。


#####ConsumeMessageService
* 消费消息服务，不断不断不断消费消息，并处理消费结果。
* Consumer 消费进度

#####RemoteBrokerOffsetStore
* Consumer 消费进度管理，负责从 Broker 获取消费进度，同步消费进度到 Broker。

#### OffsetStore 
* Consumer 消费进度
* RemoteBrokerOffsetStore ：Consumer 集群模式 下，使用远程 Broker 消费进度。
* LocalFileOffsetStore ：Consumer 广播模式下，使用本地 文件 消费进度 从本地文件加载消费进度到内存

#####ProcessQueue 
* 消息处理队列。


#####MQClientInstance 
* 封装对 Namesrv，Broker 的 API调用，提供给 Producer、Consumer 使用。


#### AllocateMessageQueueAveragely
* 平均分配队列策略。
#### AllocateMessageQueueAveragelyByCircle
* 环状分配消息队列。
####AllocateMessageQueueByConfig
* 分配配置的消息队列。

#### ClientLogger
* 日志配置类 eg: System.setProperty(ClientLogger.CLIENT_LOG_USESLF4J, "true");
        
##重要说明 ：
* 消费进度持久化不仅仅只有定时持久化，拉取消息、分配消息队列等等操作，都会进行消费进度持久化。