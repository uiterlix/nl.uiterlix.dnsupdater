# DNSUpdater

DNSUpdater is a Kotlin-based application that updates DNS A records in DirectAdmin 
for specified subdomains whenever the external IP address changes.
It also sends notifications via MQTT when the IP address changes.

## Features

- Checks the current external IP address.
- Compares the current IP address with the stored IP address.
- Updates DNS A records if the IP address has changed.
- Sends notifications via MQTT when the IP address changes.

## Prerequisites

- Docker
- Docker Hub account
- Java 19
- Gradle

## Installation

1. **Clone the repository:**

    ```sh
    git clone https://github.com/uiterlix/dnsupdater.git
    cd dnsupdater
    ```

2. **Build the Docker image:**

    ```sh
    docker build -t your-dockerhub-username/dnsupdater:latest .
    ```

3. **Push the Docker image to Docker Hub:**

    ```sh
    docker login
    docker push your-dockerhub-username/dnsupdater:latest
    ```

## Usage

1. **Create a `settings.json` file:**

    ```json
    {
      "host": "web0101.zxcs.nl",
      "port": 2222,
      "user": "<directadmin user>",
      "password": "<directadmin password>",
      "domain": "<domain without subdomain>",
      "mqttHost": "192.168.2.91",
      "mqttPort": 1883,
      "mqttTopic": "telegram_urgent",
      "subDomains": ["a", "b", "c"]
    }
    ```

2. **Run the Docker container:**

    ```sh
    docker run -v /path/to/settings.json:/config/settings.json your-dockerhub-username/dnsupdater:latest
    ```

3. **Set up a cron job to run the DNSUpdater every 5 minutes:**

   Create a `crontab` file with the following content:

    ```cron
    */5 * * * * /usr/local/openjdk-19/bin/java -jar /app/build/libs/DNSUpdater.jar /config/settings.json >> /var/log/cron.log 2>&1
    ```

   Ensure the `crontab` file is copied to the Docker image and applied as shown in the `Dockerfile`.

## License

This project is licensed under the Apache 2.0 License. See the `LICENSE` file for details.

## Acknowledgments

- [Eclipse Paho MQTT](https://www.eclipse.org/paho/)
- [Kotlin](https://kotlinlang.org/)
- [Gradle](https://gradle.org/)

## Contributing

Contributions are welcome! Please open an issue or submit a pull request for any improvements or bug fixes.