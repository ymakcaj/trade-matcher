# ---- Stage 1: Build The App ----
# Use an official OpenJDK image that includes Maven
FROM maven:3.8-openjdk-17 AS build

# Set the working directory
WORKDIR /app

# Copy the Maven project file and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy the rest of your source code and build the application
COPY src ./src
RUN mvn package -DskipTests

# ---- Stage 2: Create The Final Image ----
# Use a lightweight JRE image for the smallest size
FROM eclipse-temurin:17-jre-focal

WORKDIR /app

# Copy the .jar file built in Stage 1
COPY --from=build /app/target/*.jar app.jar

# IMPORTANT: Tell your host what port your app uses
# Render will automatically set a PORT environment variable
# Your Javalin app should listen on this port
EXPOSE 7070

# Command to run your application
# Use $PORT if provided, otherwise default to 7070
CMD ["java", "-jar", "app.jar"]