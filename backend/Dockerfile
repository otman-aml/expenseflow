# ---------- Etape 1 : compilation ----------
# Image multi-etapes : le JDK et Maven servent a compiler, pas a executer.
# L'image finale ne contient que le JRE et le jar, ce qui divise le poids final
# et supprime Maven et les dependances de build de l'image deployee.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# Les dependances sont copiees avant le code source : tant que le pom.xml ne
# change pas, cette couche reste dans le cache Docker. Un simple changement de
# code ne retelecharge donc pas toutes les dependances.
COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src

# Les tests sont volontairement IGNORES dans le build d'image.
#
# Raison : Testcontainers a besoin d'un demon Docker, absent dans un buildkit.
# Les tests d'integration echouent donc tous ici, et feraient echouer le build
# d'une image parfaitement saine. La suite complete reste une verification
# locale : `cd backend && mvn clean test`.
RUN mvn -B clean package -DskipTests

# ---------- Etape 2 : execution ----------
FROM eclipse-temurin:21-jre

WORKDIR /app

# Utilisateur non root : l'application n'a aucune raison de s'executer en
# root, et un conteneur compromis ne dispose alors d'aucun privilege systeme.
RUN useradd --system --create-home --shell /usr/sbin/nologin expenseflow
USER expenseflow

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8090

# Sonde de disponibilite : /actuator/health est declare public par
# SecurityConfig, et ne reclame aucune authentification. Elle permet au
# compose et a Render de distinguer « demarre » de « pret a repondre ».
#
# 127.0.0.1 et non "localhost" : dans une image slim, "localhost" peut se
# resoudre en IPv6 alors que le serveur n'ecoute qu'en IPv4.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 CMD wget -qO- http://127.0.0.1:8090/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
