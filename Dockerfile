# Use an official OpenJDK runtime as a parent image
FROM openjdk:19-jdk-slim

# Install cron
RUN apt-get update && apt-get install -y cron

# Set the working directory in the container
WORKDIR /app

# Copy the current directory contents into the container at /app
COPY . /app

# Build the application
RUN ./gradlew build

VOLUME /config

# Copy the crontab file to the cron.d directory
COPY crontab /etc/cron.d/dnsupdater-cron

# Give execution rights on the cron job
RUN chmod 0644 /etc/cron.d/dnsupdater-cron

# Apply cron job
RUN crontab /etc/cron.d/dnsupdater-cron

# Create the log file to be able to run tail
RUN touch /var/log/cron.log

# Run the command on container startup
CMD cron && tail -f /var/log/cron.log

# Run the application
# CMD ["java", "-jar", "build/libs/DNSUpdater.jar", "/config/settings.json"]