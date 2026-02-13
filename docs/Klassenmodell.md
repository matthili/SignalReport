@startuml Internet-Qualitätsmonitor
!theme vibrant
skinparam classFontSize 13
skinparam packageStyle rectangle

package "measurement" {
  interface Measurer {
    + String getType()
    + double measureLatency() throws MeasurementException
  }

  class PingMeasurer {
    - InetAddress target
    + double measureLatency()
  }

  class DnsMeasurer {
    - String hostname
    + double measureLatency()
  }

  class HttpMeasurer {
    - URI endpoint
    + double measureLatency()
  }
}

package "model" {
  class Measurement {
    - long id
    - Instant timestamp
    - String type
    - double latencyMs
    - boolean success
    - String errorMessage
  }
}

package "persistence" {
  interface MeasurementRepository {
    + void save(Measurement m)
    + List<Measurement> findLastN(int n, String type)
    + List<Measurement> findByDateRange(Instant from, Instant to)
  }

  class H2MeasurementRepository {
    - String jdbcUrl
    + void save(Measurement m)
    + List<Measurement> findLastN(int n, String type)
  }
}

package "scheduler" {
  class MeasurementScheduler {
    - List<Measurer> measurers
    - MeasurementRepository repo
    - ScheduledExecutorService executor
    + void start(int intervalSeconds)
    + void stop()
  }
}

package "api" {
  class RestApi {
    - MeasurementRepository repo
    + void start(int port)
  }
}

package "export" {
  class PdfReportGenerator {
    - MeasurementRepository repo
    + File generateReport(Instant from, Instant to)
  }
}

package "main" {
  class Application {
    {static} + void main(String[] args)
  }
}

Measurer <|.. PingMeasurer
Measurer <|.. DnsMeasurer
Measurer <|.. HttpMeasurer

MeasurementRepository <|.. H2MeasurementRepository

MeasurementScheduler --> Measurer : nutzt
MeasurementScheduler --> MeasurementRepository : speichert in

RestApi --> MeasurementRepository : liest aus

PdfReportGenerator --> MeasurementRepository : liest aus

Application --> MeasurementScheduler : startet
Application --> RestApi : startet
Application --> H2MeasurementRepository : initialisiert

note top of Measurement
  {field} id : BIGINT (PK)
  timestamp : TIMESTAMP
  type : VARCHAR(20) -- "PING", "DNS", "HTTP"
  latency_ms : DOUBLE
  success : BOOLEAN
  error_message : VARCHAR(255)
end note

note "H2 im FILE-Modus:\njdbc:h2:./data/metrics" as N1
H2MeasurementRepository .. N1
@enduml