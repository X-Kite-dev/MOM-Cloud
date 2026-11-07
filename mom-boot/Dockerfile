FROM 192.0.2.1:5001/devops/java:21-anolis

WORKDIR /mom-boot

ARG JAR_FILE=target/mom-boot.jar

COPY ${JAR_FILE} app.jar

EXPOSE 9999

ENV TZ=Asia/Shanghai JAVA_OPTS="-Xms512m -Xmx1024m -Djava.security.egd=file:/dev/./urandom"

CMD java $JAVA_OPTS -jar app.jar
