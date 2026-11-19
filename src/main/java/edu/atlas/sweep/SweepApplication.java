package edu.atlas.sweep;

import edu.atlas.sweep.domain.City;
import edu.atlas.sweep.service.BenchmarkService;
import edu.atlas.sweep.service.PlannerService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@SpringBootApplication
public class SweepApplication {

    public static void main(String[] args) {
        SpringApplication.run(SweepApplication.class, args);
    }

    @Bean
    City city(@Value("${sweep.data-dir:data}") String dir, @Value("${sweep.seed:42}") long seed) {
        return City.load(Path.of(dir), seed);
    }

    @Bean
    PlannerService planner(City city, @Value("${sweep.seed:42}") long seed) {
        return new PlannerService(city, seed);
    }

    @Bean
    BenchmarkService benchmarks() {
        return new BenchmarkService();
    }
}
