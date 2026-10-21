# 백엔드 서버 이미지: 1단계에서 실행용 jar를 만들고, 2단계에서 JRE만 있는 가벼운 이미지로 옮긴다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar -x test --no-daemon && cp build/libs/*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app
ENV TZ=Asia/Seoul
COPY --from=build /app/app.jar app.jar
EXPOSE 8080
# 메모리 2GB 서버에서 DB와 함께 돌도록 힙을 제한한다.
ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
