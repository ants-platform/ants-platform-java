# ants-platform-java

This repository contains an auto-generated ANTS Platform API client for Java based on our API specification.
See the ANTS Platform API reference for more details on the available endpoints.

## Installation

The recommended way to install the ants-platform-java API client is via Maven Central:

```xml
<dependency>
    <groupId>ai.agenticants</groupId>
    <artifactId>ants-platform-java</artifactId>
    <version>0.1.1</version>
</dependency>
```

## Usage

Instantiate the ANTS Platform Client with the respective endpoint and your API Keys.

```java
import com.ants.platform.client.AntsPlatformClient;

AntsPlatformClient client = AntsPlatformClient.builder()
        .url("https://api.agenticants.ai")
        .credentials("your-public-key", "your-secret-key")
        .build();
```

Make requests using the clients:

```java
import com.ants.platform.client.core.AntsPlatformClientApiException;
import com.ants.platform.client.resources.prompts.types.PromptMetaListResponse;

try {
    PromptMetaListResponse prompts = client.prompts().list();
} catch (AntsPlatformClientApiException error) {
    System.out.println(error.getBody());
    System.out.println(error.getStatusCode());
}
```

## Drafting a Release

Run `./mvnw release:prepare -DreleaseVersion=` with the version you want to create.
Push the changes including the tag.

## Publishing to Maven Central

This project is configured to publish to Maven Central.
To publish to Maven Central, you need to configure the following secrets in your GitHub repository:

- `OSSRH_USERNAME`: Your Sonatype OSSRH username
- `OSSRH_PASSWORD`: Your Sonatype OSSRH password
- `GPG_PRIVATE_KEY`: Your GPG private key for signing artifacts
- `GPG_PASSPHRASE`: The passphrase for your GPG private key

## Updating

1. Ensure that ants-platform-java is placed in the same directory as the main API specification repository.
2. Setup a new Java fern generator using
   ```yaml
      - name: fernapi/fern-java-sdk
        version: 2.20.1
        output:
          location: local-file-system
          path: ../../../../ants-platform-java/src/main/java/com/ants/platform/client/
        config:
          client-class-name: AntsPlatformClient
   ```
3. Generate the new client code using `npx fern-api generate --api server`.
4. Manually set the `package` across all files to `com.ants.platform.client`.
5. Overwrite `this.clientOptionsBuilder.addHeader("Authorization", "Bearer " + encodedToken);` to `Basic` in AntsPlatformClientBuilder.java.
6. Commit the changes in ants-platform-java and push them to the repository.
