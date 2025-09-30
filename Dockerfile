# syntax=docker/dockerfile:1

# --- build stage ---
FROM gradle:8.10.2-jdk21 AS build
WORKDIR /src

# Кэшируем зависимости: сначала wrapper + gradle метаданные
COPY gradle gradle
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
RUN chmod +x gradlew
# Тёплый кэш зависимостей для ускорения последующих билдов
RUN ./gradlew --no-daemon dependencies

# Теперь копируем исходники
COPY src ./src

# Сборка дистрибутива (без тестов для скорости)
RUN ./gradlew --no-daemon clean installDist -x test

# --- runtime stage ---
FROM eclipse-temurin:21-jre
ENV APP_HOME=/app
WORKDIR $APP_HOME

# Копируем готовый дистрибутив
COPY --from=build /src/build/install/zeiterfassung-server $APP_HOME

# Безопаснее запускать не от root
RUN useradd -m appuser && chown -R appuser:appuser $APP_HOME
USER appuser

# Порт по умолчанию; Koyeb передаст свой PORT через env
ENV PORT=8080
EXPOSE 8080

# Запускаем скрипт из installDist
CMD ["./bin/zeiterfassung-server"]