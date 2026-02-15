package com.ai.platform.api.finance;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/transactions")
public class TransactionsController {

    private final TransactionsService transactionsService;

    public TransactionsController(TransactionsService transactionsService) {
        this.transactionsService = transactionsService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("files") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No files uploaded"));
        }

        // Optional: reject empty files cleanly
        boolean allEmpty = files.stream().allMatch(f -> f == null || f.isEmpty());
        if (allEmpty) {
            return ResponseEntity.badRequest().body(Map.of("error", "All uploaded files are empty"));
        }

        // Multi-file: parse + merge deterministically in service, then AI categorize in safe chunks
        return ResponseEntity.ok(transactionsService.aiAnalyze(files));
    }
}
