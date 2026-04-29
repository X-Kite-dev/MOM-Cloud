FROM 172.25.2.27:5001/devops/java:21-anolis

MAINTAINER kitelcdp@163.com

ENV TZ=Asia/Shanghai
ENV LANG C.UTF-8
ENV JAVA_OPTS="-Xms512m -Xmx1024m -Djava.security.egd=file:/dev/./urandom"

RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

RUN mkdir -p /mom-pms

WORKDIR /mom-pms

EXPOSE 33000

ADD ./target/mom-pms-biz.jar ./

CMD java $JAVA_OPTS -jar mom-pms-biz.jar
