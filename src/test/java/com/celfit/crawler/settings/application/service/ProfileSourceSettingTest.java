package com.celfit.crawler.settings.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.celfit.crawler.settings.application.port.out.AppSettingRepository;
import com.celfit.crawler.settings.domain.AppSetting;
import com.celfit.crawler.settings.domain.ProfileSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

public class ProfileSourceSettingTest {

    // AppSettingRepository의 최소 fake (findById/save만 사용) — 다른 패키지 테스트(ProfileSupplementerTest)도 재사용
    public static AppSettingRepository fakeRepo(Map<String, String> store) {
        return new AppSettingRepository() {
            @Override public Optional<AppSetting> findById(String k) {
                return Optional.ofNullable(store.get(k)).map(v -> new AppSetting(k, v));
            }
            @Override public <S extends AppSetting> S save(S e) { store.put(e.getKey(), e.getValue()); return e; }
            // 나머지 JpaRepository 메서드는 이 테스트에서 미사용 → default 예외
            @Override public java.util.List<AppSetting> findAll() { throw new UnsupportedOperationException(); }
            @Override public java.util.List<AppSetting> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> java.util.List<S> saveAll(Iterable<S> es) { throw new UnsupportedOperationException(); }
            @Override public boolean existsById(String id) { return store.containsKey(id); }
            @Override public long count() { return store.size(); }
            @Override public void deleteById(String id) { store.remove(id); }
            @Override public void delete(AppSetting e) { store.remove(e.getKey()); }
            @Override public void deleteAllById(Iterable<? extends String> ids) { ids.forEach(store::remove); }
            @Override public void deleteAll(Iterable<? extends AppSetting> es) { es.forEach(this::delete); }
            @Override public void deleteAll() { store.clear(); }
            @Override public java.util.List<AppSetting> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
            @Override public org.springframework.data.domain.Page<AppSetting> findAll(org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
            @Override public void flush() {}
            @Override public <S extends AppSetting> S saveAndFlush(S e) { return save(e); }
            @Override public <S extends AppSetting> java.util.List<S> saveAllAndFlush(Iterable<S> es) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllInBatch(Iterable<AppSetting> es) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
            @Override public void deleteAllInBatch() { store.clear(); }
            @Override public AppSetting getReferenceById(String id) { throw new UnsupportedOperationException(); }
            @Override public AppSetting getOne(String id) { throw new UnsupportedOperationException(); }
            @Override public AppSetting getById(String id) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> Optional<S> findOne(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> java.util.List<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> ex, org.springframework.data.domain.Pageable p) { throw new UnsupportedOperationException(); }
            @Override public <S extends AppSetting> long count(org.springframework.data.domain.Example<S> ex) { return 0; }
            @Override public <S extends AppSetting> boolean exists(org.springframework.data.domain.Example<S> ex) { return false; }
            @Override public <S extends AppSetting, R> R findBy(org.springframework.data.domain.Example<S> ex, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> f) { throw new UnsupportedOperationException(); }
        };
    }

    @Test void 기본값은_SELF() {
        var setting = new ProfileSourceSetting(fakeRepo(new HashMap<>()));
        assertThat(setting.current()).isEqualTo(ProfileSource.SELF);
    }

    @Test void 저장한_값을_읽는다() {
        var setting = new ProfileSourceSetting(fakeRepo(new HashMap<>()));
        setting.update(ProfileSource.HIKER_MOBILE);
        assertThat(setting.current()).isEqualTo(ProfileSource.HIKER_MOBILE);
    }

    @Test void 이상한_값이면_SELF로_폴백() {
        var store = new HashMap<String, String>();
        var setting = new ProfileSourceSetting(fakeRepo(store));
        setting.updateRaw("GARBAGE");
        assertThat(setting.current()).isEqualTo(ProfileSource.SELF);
    }
}
