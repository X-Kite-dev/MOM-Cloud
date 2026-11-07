FROM 192.0.2.1:5001/devops/java:21-anolis

MAINTAINER contact@example.invalid

ENV TZ=Asia/Shanghai
ENV LANG C.UTF-8
ENV JAVA_OPTS="-Xms512m -Xmx1024m -Djava.security.egd=file:/dev/./urandom"

RUN ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

RUN mkdir -p /mom-common

WORKDIR /mom-common

EXPOSE 34000

ADD ./target/mom-common-biz.jar ./

CMD java $JAVA_OPTS -jar mom-common-biz.jar
