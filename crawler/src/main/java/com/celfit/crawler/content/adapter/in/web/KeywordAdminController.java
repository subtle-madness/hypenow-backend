package com.celfit.crawler.content.adapter.in.web;

import com.celfit.crawler.content.application.port.out.SearchKeywordRepository;
import com.celfit.crawler.content.domain.SearchKeyword;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 검색 키워드 CRUD — 카테고리 계층 제거 후 평탄화. */
@RestController
@RequestMapping("/admin/keywords")
public class KeywordAdminController {

    public record KeywordReq(@NotBlank String keyword) {}

    /** enabled만 수정 가능 — keyword는 여기 없다(수정 = 삭제 + 추가). */
    public record EnabledReq(@NotNull Boolean enabled) {}

    public record KeywordView(Long id, String keyword, boolean enabled) {
        static KeywordView from(SearchKeyword k) {
            return new KeywordView(k.getId(), k.getKeyword(), k.isEnabled());
        }
    }

    private final SearchKeywordRepository keywords;

    public KeywordAdminController(SearchKeywordRepository keywords) {
        this.keywords = keywords;
    }

    @GetMapping
    public List<KeywordView> list() {
        return keywords.findAllByOrderByKeywordAsc().stream().map(KeywordView::from).toList();
    }

    @PostMapping
    public ResponseEntity<KeywordView> create(@Valid @RequestBody KeywordReq req) {
        String value = req.keyword().trim();
        if (keywords.findByKeyword(value).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이미 등록된 키워드");
        }
        SearchKeyword saved = keywords.save(new SearchKeyword(value, Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED).body(KeywordView.from(saved));
    }

    @PutMapping("/{id}")
    public KeywordView setEnabled(@PathVariable Long id, @Valid @RequestBody EnabledReq req) {
        SearchKeyword keyword = keywords.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "키워드 없음"));
        keyword.setEnabled(req.enabled());
        return KeywordView.from(keywords.save(keyword));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        keywords.deleteById(id);
    }
}
