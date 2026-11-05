# Build em duas etapas: o JDK compila, e só o JRE (mais o jar) vai para a imagem final.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /app
# as dependências antes do código: a camada só é refeita quando o pom muda
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
# os testes rodam no CI e em ./mvnw verify; a imagem só empacota
RUN ./mvnw -B -ntp -o -DskipTests package && cp target/aws-auditor-*.jar app.jar

FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /app/app.jar app.jar
# sem root dentro do contêiner (UID numérico: o ECS e o Kubernetes conseguem verificar runAsNonRoot)
USER 10001:10001
EXPOSE 8080
# Por padrão sobe a API. Como JOB (o uso do agendador): AUDITOR_JOB_HABILITADO=true e
# SPRING_MAIN_WEB_APPLICATION_TYPE=none; ele roda uma auditoria e sai com código 0 (ok) ou 1 (falhou).
# Nada de segredo aqui: o token do Telegram e a chave da API chegam por variável de ambiente (SSM, no ECS).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
