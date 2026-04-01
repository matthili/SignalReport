# SignalReport Docker Image
FROM eclipse-temurin:21-jre-alpine

# Arbeitsverzeichnis
WORKDIR /app

# Port freigeben
EXPOSE 4567

# SignalReport JAR kopieren
COPY target/signalreport-1.0.jar signalreport.jar

# Datenverzeichnis für persistente Konfiguration
VOLUME /app/data

# Start-Befehl
CMD ["java", "-jar", "signalreport.jar"]