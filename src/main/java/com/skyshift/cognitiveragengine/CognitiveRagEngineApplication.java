package com.skyshift.cognitiveragengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class CognitiveRagEngineApplication {

	public static void main(String[] args) {
		SpringApplication.run(CognitiveRagEngineApplication.class, args);
	}

}
