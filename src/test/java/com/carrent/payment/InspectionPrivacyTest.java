package com.carrent.payment;

import com.carrent.inspection.InspectionForm;
import com.carrent.inspection.InspectionPhase;
import com.carrent.inspection.InspectionService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.carrent.inspection.BookingInspection;
import com.carrent.common.exception.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

class InspectionPrivacyTest {
    static PaymentFlowFixture f;
    static InspectionService service;
    @TempDir static Path root;
    @BeforeAll static void start() throws Exception {
        System.setProperty("carrent.inspection.storage-root", root.toString());
        try { f = new PaymentFlowFixture(false, false); }
        finally { System.clearProperty("carrent.inspection.storage-root"); }
        service = f.app.getBean(InspectionService.class);
    }
    @AfterAll static void stop() throws Exception { if (f != null) f.close(); }
    @BeforeEach void reset() throws Exception {
        f.jdbc.update("DELETE FROM inspection_events");
        f.jdbc.update("DELETE FROM inspection_images");
        f.jdbc.update("DELETE FROM booking_inspections");
        try (var files = Files.list(root)) { for (Path file : files.toList()) Files.delete(file); }
        f.reset();
        f.jdbc.update("""
                UPDATE bookings SET workflow_version='VIETQR_V1', inspection_required=1,
                vehicle_received_at=NULL, vehicle_returned_at=NULL,
                contract_confirmed_at=NULL, contract_confirmed_by=NULL WHERE id=1
                """);
    }

    @Test void readingDoesNotCreateADraftAndSavingAnIncompleteDraftIsRepeatable() {
        var actor = f.actor(2);
        assertThat(service.getView(1L, InspectionPhase.BEFORE, actor).reportId()).isNull();
        assertThat(f.count("booking_inspections")).isZero();
        var form = new InspectionForm(); form.setConditionNote("  Xe sạch  ");
        service.saveDraft(1L, InspectionPhase.BEFORE, form, actor);
        var first = service.getView(1L, InspectionPhase.BEFORE, actor);
        assertThat(first.conditionNote()).isEqualTo("Xe sạch");
        assertThat(first.odometerKm()).isNull();
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), actor);
        assertThat(service.getView(1L, InspectionPhase.BEFORE, actor).reportId()).isEqualTo(first.reportId());
        assertThat(f.count("booking_inspections")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(longs={1,3,4,5})
    void draftIsPrivateEvenToTheOtherMemberAndAdmin(long userId) {
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(2));
        assertThatThrownBy(() -> service.getView(1L, InspectionPhase.BEFORE, f.actor(userId)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(userId)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test void inactiveAccountCannotUseAPreviouslyAuthenticatedSession() {
        var actor = f.actor(2);
        f.jdbc.update("UPDATE users SET status='BANNED' WHERE id=2");
        assertThatThrownBy(() -> service.getView(1L, InspectionPhase.BEFORE, actor)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), actor))
                .isInstanceOf(ForbiddenException.class);
    }

    @ParameterizedTest @CsvSource({"km,-1", "km,10000000", "energy,-1", "energy,101"})
    void invalidDraftNumbersCannotBePersisted(String field, int value) {
        var form = new InspectionForm();
        if (field.equals("km")) form.setOdometerKm(value); else form.setEnergyPercent(value);
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, form, f.actor(2)))
                .isInstanceOf(BusinessException.class);
        assertThat(f.count("booking_inspections")).isZero();
    }

    @ParameterizedTest @CsvSource({"condition,2001", "damage,1001", "accessories,501"})
    void oversizedDraftTextCannotBePersisted(String field, int size) {
        var form = new InspectionForm();
        switch (field) {
            case "condition" -> form.setConditionNote("a".repeat(size));
            case "damage" -> form.setVisibleDamage("a".repeat(size));
            default -> form.setAccessoriesNote("a".repeat(size));
        }
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, form, f.actor(2)))
                .isInstanceOf(BusinessException.class);
        assertThat(f.count("booking_inspections")).isZero();
    }

    @Test void afterDraftRequiresHandoverAndBelongsToCustomer() {
        var customer = f.actor(4);
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.AFTER, new InspectionForm(), customer))
                .isInstanceOf(BusinessException.class);
        handover();
        service.saveDraft(1L, InspectionPhase.AFTER, new InspectionForm(), customer);
        assertThat(service.getView(1L, InspectionPhase.AFTER, customer).editable()).isTrue();
        assertThatThrownBy(() -> service.getView(1L, InspectionPhase.AFTER, f.actor(2))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(2)))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest @ValueSource(strings={"CANCELLED","REJECTED","EXPIRED","COMPLETED"})
    void closedBookingsCannotAcquireDrafts(String status) {
        f.jdbc.update("UPDATE bookings SET status=? WHERE id=1", status);
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(2)))
                .isInstanceOf(BusinessException.class);
        assertThat(f.count("booking_inspections")).isZero();
    }

    @Test void legacyBookingDoesNotAcquireSyntheticInspectionEvidence() {
        f.jdbc.update("UPDATE bookings SET workflow_version='LEGACY',inspection_required=0 WHERE id=1");
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(2)))
                .isInstanceOf(NotFoundException.class);
        assertThat(f.count("booking_inspections")).isZero();
    }

    @Test void currentSubmittedStateCannotBeOverwrittenUsingAStaleManagedDraft() {
        var owner = f.actor(2);
        var form = new InspectionForm(); form.setConditionNote("Bản gốc");
        service.saveDraft(1L, InspectionPhase.BEFORE, form, owner);
        Long id = service.getView(1L, InspectionPhase.BEFORE, owner).reportId();
        var tx = new TransactionTemplate(f.app.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(s -> {
            s.setRollbackOnly();
            f.em.find(BookingInspection.class, id);
            Long connection = f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            try {
                Long other = CompletableFuture.supplyAsync(() -> f.tx.execute(t -> {
                    f.jdbc.update("UPDATE booking_inspections SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP(6) WHERE id=?", id);
                    return f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
                })).get(15, TimeUnit.SECONDS);
                assertThat(other).isNotEqualTo(connection);
            } catch (Exception failure) { throw new AssertionError(failure); }
            var changed = new InspectionForm(); changed.setConditionNote("Không được ghi đè");
            assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, changed, owner))
                    .isInstanceOf(BusinessException.class);
        });
        assertThat(service.getView(1L, InspectionPhase.BEFORE, owner).conditionNote()).isEqualTo("Bản gốc");
    }

    private void handover() {
        f.jdbc.update("""
                UPDATE bookings SET status='CONFIRMED',vehicle_received_at='2026-10-31 03:00:00',
                contract_confirmed_at='2026-10-31 03:00:00',contract_confirmed_by=2 WHERE id=1
                """);
    }

    @Test void authorCanUploadReadAndDeleteDraftImages() throws Exception {
        var owner = f.actor(2);
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        var file = photo();
        service.addImages(1L, InspectionPhase.BEFORE, List.of(file), owner);
        var view = service.getView(1L, InspectionPhase.BEFORE, owner);
        assertThat(view.images()).hasSize(1);
        Long imageId = view.images().getFirst().id();
        var content = service.readImage(1L, InspectionPhase.BEFORE, imageId, owner);
        assertThat(content.mediaType()).isEqualTo("image/png");
        assertThat(content.resource().getContentAsByteArray()).isEqualTo(file.getBytes());
        service.deleteDraftImage(1L, InspectionPhase.BEFORE, imageId, owner);
        assertThat(service.getView(1L, InspectionPhase.BEFORE, owner).images()).isEmpty();
        assertThat(files()).isZero();
    }

    private static MockMultipartFile photo() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("images", "client.png", "image/png", out.toByteArray());
    }
    private long files() {
        try (var files = Files.list(root)) { return files.count(); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    @Test void rollbackOfAnUploadRemovesItsFileAndDatabaseRow() throws Exception {
        var owner = f.actor(2); var photo = photo();
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        f.tx.executeWithoutResult(s -> {
            service.addImages(1L, InspectionPhase.BEFORE, List.of(photo), owner);
            assertThat(files()).isEqualTo(1);
            s.setRollbackOnly();
        });
        assertThat(service.getView(1L, InspectionPhase.BEFORE, owner).images()).isEmpty();
        assertThat(files()).isZero();
    }

    @Test void aBadSecondImageRollsBackTheEntireBatch() throws Exception {
        var owner = f.actor(2);
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        var good = photo(); var bad = new MockMultipartFile("images", "a.png", "image/png", "not an image".getBytes());
        assertThatThrownBy(() -> service.addImages(1L, InspectionPhase.BEFORE, List.of(good, bad), owner))
                .isInstanceOf(BusinessException.class);
        assertThat(service.getView(1L, InspectionPhase.BEFORE, owner).images()).isEmpty();
        assertThat(files()).isZero();
    }

    @Test void rollbackOfADeletionKeepsTheOriginalImageAvailable() throws Exception {
        var owner = f.actor(2);
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        service.addImages(1L, InspectionPhase.BEFORE, List.of(photo()), owner);
        Long id = service.getView(1L, InspectionPhase.BEFORE, owner).images().getFirst().id();
        f.tx.executeWithoutResult(s -> {
            service.deleteDraftImage(1L, InspectionPhase.BEFORE, id, owner);
            assertThat(files()).isEqualTo(1);
            s.setRollbackOnly();
        });
        assertThat(service.readImage(1L, InspectionPhase.BEFORE, id, owner).resource().exists()).isTrue();
        assertThat(files()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(longs={1,3,4,5})
    void draftPhotosCannotBeReadOrDeletedByOtherAccounts(long userId) throws Exception {
        var owner = f.actor(2);
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        service.addImages(1L, InspectionPhase.BEFORE, List.of(photo()), owner);
        Long id = service.getView(1L, InspectionPhase.BEFORE, owner).images().getFirst().id();
        var other = f.actor(userId);
        assertThatThrownBy(() -> service.readImage(1L, InspectionPhase.BEFORE, id, other)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteDraftImage(1L, InspectionPhase.BEFORE, id, other)).isInstanceOf(NotFoundException.class);
        assertThat(files()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"SUBMITTED","ACCEPTED","DISPUTED","RESOLVED"})
    void submittedEvidenceIsVisibleToMembersAndAdminButAlwaysImmutable(String status) throws Exception {
        var owner = f.actor(2); var photo = photo();
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        service.addImages(1L, InspectionPhase.BEFORE, List.of(photo), owner);
        Long id = service.getView(1L, InspectionPhase.BEFORE, owner).images().getFirst().id();
        f.jdbc.update("UPDATE booking_inspections SET status=?,submitted_at=CURRENT_TIMESTAMP(6)", status);
        for (long userId : new long[]{1,2,4}) {
            var actor = f.actor(userId);
            assertThat(service.getView(1L, InspectionPhase.BEFORE, actor).editable()).isFalse();
            assertThat(service.readImage(1L, InspectionPhase.BEFORE, id, actor).resource().getContentAsByteArray()).isEqualTo(photo.getBytes());
        }
        for (long userId : new long[]{3,5}) {
            var actor = f.actor(userId);
            assertThatThrownBy(() -> service.readImage(1L, InspectionPhase.BEFORE, id, actor)).isInstanceOf(NotFoundException.class);
        }
        assertThatThrownBy(() -> service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.addImages(1L, InspectionPhase.BEFORE, List.of(photo), owner)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.deleteDraftImage(1L, InspectionPhase.BEFORE, id, owner)).isInstanceOf(BusinessException.class);
        assertThat(files()).isEqualTo(1);
    }

    @Test void imageIdsCannotBeMovedBetweenReports() throws Exception {
        var owner = f.actor(2); var customer = f.actor(4);
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        service.addImages(1L, InspectionPhase.BEFORE, List.of(photo()), owner);
        Long beforeId = service.getView(1L, InspectionPhase.BEFORE, owner).images().getFirst().id();
        handover();
        service.saveDraft(1L, InspectionPhase.AFTER, new InspectionForm(), customer);
        assertThatThrownBy(() -> service.readImage(1L, InspectionPhase.AFTER, beforeId, customer)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteDraftImage(1L, InspectionPhase.AFTER, beforeId, customer)).isInstanceOf(NotFoundException.class);
        assertThat(files()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(ints={0,5})
    void invalidBatchSizeCannotWriteFiles(int size) throws Exception {
        var owner = f.actor(2);
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        var batch = java.util.Collections.<org.springframework.web.multipart.MultipartFile>nCopies(size, photo());
        assertThatThrownBy(() -> service.addImages(1L, InspectionPhase.BEFORE, batch, owner)).isInstanceOf(BusinessException.class);
        assertThat(files()).isZero();
    }

    @Test void concurrentUploadsCannotExceedTenImagesOnDifferentConnections() throws Exception {
        var owner = f.actor(2); var photo = photo();
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        service.addImages(1L, InspectionPhase.BEFORE, java.util.Collections.nCopies(4, photo), owner);
        service.addImages(1L, InspectionPhase.BEFORE, java.util.Collections.nCopies(4, photo), owner);
        service.addImages(1L, InspectionPhase.BEFORE, List.of(photo), owner);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var connections = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        java.util.concurrent.Callable<Boolean> upload = () -> {
            try {
                f.tx.executeWithoutResult(s -> {
                    connections.add(f.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    try { barrier.await(15, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); }
                    service.addImages(1L, InspectionPhase.BEFORE, List.of(photo), owner);
                });
                return true;
            } catch (BusinessException expected) { return false; }
        };
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = pool.submit(upload); var b = pool.submit(upload);
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(connections).doesNotHaveDuplicates().hasSize(2);
        assertThat(service.getView(1L, InspectionPhase.BEFORE, owner).images()).hasSize(10);
        assertThat(files()).isEqualTo(10);
    }

    @Test void draftValidationAllowsNumericBoundariesButSubmissionRequiresAllFiveFields() {
        var form = new InspectionForm(); form.setOdometerKm(9999999); form.setEnergyPercent(0);
        service.saveDraft(1L, InspectionPhase.BEFORE, form, f.actor(2));
        assertThat(service.getView(1L, InspectionPhase.BEFORE, f.actor(2)).odometerKm()).isEqualTo(9999999);
        var validator = f.app.getBean(jakarta.validation.Validator.class);
        assertThat(validator.validate(new InspectionForm(), InspectionForm.Submitted.class)).hasSize(5);
        form.setConditionNote("Xe tốt"); form.setVisibleDamage("Không phát hiện"); form.setAccessoriesNote("Không bàn giao");
        assertThat(validator.validate(form, InspectionForm.Submitted.class)).isEmpty();
    }

    @Test void httpDraftFormReadsWithoutInsertingAndPostsOnlyTheFiveEditableFields() throws Exception {
        var owner = f.actor(2);
        f.mvc.perform(get("/bookings/1/inspections/BEFORE/draft").with(user(owner)))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Tình trạng xe")));
        assertThat(f.count("booking_inspections")).isZero();
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/draft").with(user(owner)).with(csrf())
                        .param("conditionNote", "Xe sạch").param("status", "ACCEPTED").param("authorId", "1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/bookings/1/inspections/BEFORE/draft"));
        var view = service.getView(1L, InspectionPhase.BEFORE, owner);
        assertThat(view.conditionNote()).isEqualTo("Xe sạch");
        assertThat(view.status()).isEqualTo(com.carrent.inspection.InspectionStatus.DRAFT);
        assertThatThrownBy(() -> service.getView(1L, InspectionPhase.BEFORE, f.actor(1))).isInstanceOf(NotFoundException.class);
    }

    @ParameterizedTest @ValueSource(strings={"abc","1.5","2147483648","-1","10000000"})
    void httpNumberErrorsKeepInputWithoutSavingOrExecutingHtml(String km) throws Exception {
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/draft").with(user(f.actor(2))).with(csrf())
                        .param("conditionNote", "<script>alert(1)</script>").param("odometerKm", km))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(xpath("//input[@id='odometerKm']/@value").string(km))
                .andExpect(xpath("//p[@id='odometerKm-error']").exists());
        assertThat(f.count("booking_inspections")).isZero();
    }

    @Test void csrfIsRequiredForEveryNewMutation() throws Exception {
        var owner = f.actor(2);
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/draft").with(user(owner))).andExpect(status().isForbidden());
        f.mvc.perform(multipart("/bookings/1/inspections/BEFORE/images").file(photo()).with(user(owner)))
                .andExpect(status().isForbidden());
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/images/1/delete").with(user(owner))).andExpect(status().isForbidden());
        assertThat(files()).isZero();
    }

    @Test void anonymousRequestsCannotReadDraftsOrImages() throws Exception {
        for (String suffix : new String[]{"", "/draft", "/images/1"}) {
            f.mvc.perform(get("/bookings/1/inspections/BEFORE" + suffix)).andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/login"));
        }
    }

    @ParameterizedTest @ValueSource(longs={1,3,4,5})
    void httpDoesNotRevealDraftsEvenThroughInvalidPosts(long userId) throws Exception {
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), f.actor(2));
        var other = f.actor(userId);
        f.mvc.perform(get("/bookings/1/inspections/BEFORE/draft").with(user(other))).andExpect(status().isNotFound());
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/draft").with(user(other)).with(csrf())
                        .param("odometerKm", "bad")).andExpect(status().isNotFound());
        f.mvc.perform(multipart("/bookings/1/inspections/BEFORE/images").file(photo()).with(user(other)).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(files()).isZero();
    }

    @Test void authenticatedImageStreamIsPrivateAndTheFileIsNotPubliclyMapped() throws Exception {
        var owner = f.actor(2); var photo = photo();
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        f.mvc.perform(multipart("/bookings/1/inspections/BEFORE/images").file(photo).with(user(owner)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeCount(1));
        Long id = service.getView(1L, InspectionPhase.BEFORE, owner).images().getFirst().id();
        f.mvc.perform(get("/bookings/1/inspections/BEFORE/images/"+id).with(user(owner)))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(photo.getBytes())).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        String key = f.jdbc.queryForObject("SELECT storage_key FROM inspection_images WHERE id=?", String.class, id);
        f.mvc.perform(get("/uploads/"+key)).andExpect(status().isNotFound());
        f.mvc.perform(get("/private-uploads/inspections/"+key).with(user(owner))).andExpect(status().isNotFound());
        f.mvc.perform(get("/bookings/1/inspections/AFTER/images/"+id).with(user(f.actor(4)))).andExpect(status().isNotFound());
        f.mvc.perform(get("/bookings/1/inspections/UNKNOWN").with(user(owner))).andExpect(status().isNotFound());
        f.mvc.perform(get("/bookings/2/inspections/BEFORE/images/"+id).with(user(owner))).andExpect(status().isNotFound());
        f.mvc.perform(post("/bookings/1/inspections/BEFORE/images/"+id+"/delete").with(user(owner)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeCount(1));
        assertThat(files()).isZero();
    }

    @Test void httpBatchFailureReportsErrorAndLeavesNoFilesOrRows() throws Exception {
        var owner = f.actor(2);
        service.saveDraft(1L, InspectionPhase.BEFORE, new InspectionForm(), owner);
        f.mvc.perform(multipart("/bookings/1/inspections/BEFORE/images").file(photo())
                        .file(new MockMultipartFile("images", "bad.png", "image/png", new byte[]{1,2,3}))
                        .with(user(owner)).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("errorMessage"))
                .andExpect(flash().attributeCount(1));
        assertThat(service.getView(1L, InspectionPhase.BEFORE, owner).images()).isEmpty();
        assertThat(files()).isZero();
    }

    @Test void httpSubmittedViewEscapesTextAndDoesNotExposeTheDraftForm() throws Exception {
        var owner = f.actor(2); var form = new InspectionForm(); form.setConditionNote("<script>alert(1)</script>");
        service.saveDraft(1L, InspectionPhase.BEFORE, form, owner);
        f.jdbc.update("UPDATE booking_inspections SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP(6)");
        f.mvc.perform(get("/bookings/1/inspections/BEFORE").with(user(f.actor(4))))
                .andExpect(status().isOk()).andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(xpath("//textarea").doesNotExist()).andExpect(xpath("//input[@name='images']").doesNotExist());
    }
}
