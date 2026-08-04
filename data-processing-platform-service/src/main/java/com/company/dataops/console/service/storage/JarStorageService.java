package com.company.dataops.console.service.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Flink JAR bytes, held in an S3-compatible object store instead of this
 * JVM's local disk - see docker-compose.yml's `minio` service for the
 * dev/test target; jar-storage-endpoint can point at any other S3/MinIO
 * deployment (or AWS S3 itself) without a code change. forcePathStyle is
 * required for MinIO (and most non-AWS S3-compatible stores): virtual-
 * hosted-style bucket addressing (bucket.endpoint/key) needs wildcard
 * DNS/TLS the way AWS S3 has, which MinIO doesn't attempt.
 */
@Component
public class JarStorageService {
    private final S3Client s3Client;
    private final String bucket;

    public JarStorageService(
        @Value("${platform.bigdata.jar-storage-endpoint:http://localhost:19000}") String endpoint,
        @Value("${platform.bigdata.jar-storage-region:us-east-1}") String region,
        @Value("${platform.bigdata.jar-storage-bucket:flink-jars}") String bucket,
        @Value("${platform.bigdata.jar-storage-access-key:minioadmin}") String accessKey,
        @Value("${platform.bigdata.jar-storage-secret-key:minioadmin}") String secretKey
    ) {
        this.bucket = bucket;
        this.s3Client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true)
            // Without explicit timeouts, a stalled read over a flaky SSH
            // tunnel (MinIO sits behind one in the remote deployment) blocks
            // the calling Tomcat thread forever instead of failing - this
            // wedged every request once its thread pool filled up with such
            // stuck reads (see JAR upload hang during Flink job start).
            .httpClient(UrlConnectionHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(10))
                .socketTimeout(Duration.ofSeconds(30))
                .build())
            .build();
        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw exception;
            }
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        }
    }

    public void put(String key, byte[] content) {
        s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), RequestBody.fromBytes(content));
    }

    public byte[] get(String key) {
        try (ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return response.readAllBytes();
        } catch (NoSuchKeyException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件已丢失");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public void delete(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (NoSuchKeyException exception) {
            // already gone - deleting a version/jar whose object was somehow
            // removed out-of-band shouldn't block the DB-row cleanup below
        }
    }
}
