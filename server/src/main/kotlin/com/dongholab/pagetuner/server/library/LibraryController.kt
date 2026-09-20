package com.dongholab.pagetuner.server.library

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/api/v1/library/books")
class LibraryController(private val service: LibraryService) {
    @GetMapping
    fun books(principal: Principal, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int, @RequestParam(defaultValue = "") query: String) =
        service.books(principal.name, page, size, query)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun importBook(principal: Principal, @Valid @RequestBody request: ImportBookRequest) = service.importBook(principal.name, request)

    @GetMapping("/{bookId}")
    fun book(principal: Principal, @PathVariable bookId: UUID) = service.book(principal.name, bookId)

    @GetMapping("/{bookId}/chapters")
    fun chapters(principal: Principal, @PathVariable bookId: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int) = service.chapters(principal.name, bookId, page, size)

    @GetMapping("/{bookId}/chapters/{chapterId}")
    fun chapter(principal: Principal, @PathVariable bookId: UUID, @PathVariable chapterId: UUID) =
        service.chapter(principal.name, bookId, chapterId)

    @GetMapping("/{bookId}/progress")
    fun progress(principal: Principal, @PathVariable bookId: UUID) = service.progress(principal.name, bookId)

    @PutMapping("/{bookId}/progress")
    fun saveProgress(principal: Principal, @PathVariable bookId: UUID, @Valid @RequestBody request: SaveProgressRequest) =
        service.saveProgress(principal.name, bookId, request)

    @GetMapping("/{bookId}/bookmarks")
    fun bookmarks(principal: Principal, @PathVariable bookId: UUID, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int) = service.bookmarks(principal.name, bookId, page, size)

    @PostMapping("/{bookId}/bookmarks")
    @ResponseStatus(HttpStatus.CREATED)
    fun addBookmark(principal: Principal, @PathVariable bookId: UUID, @Valid @RequestBody request: SaveBookmarkRequest) =
        service.addBookmark(principal.name, bookId, request)

    @DeleteMapping("/{bookId}/bookmarks/{bookmarkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteBookmark(principal: Principal, @PathVariable bookId: UUID, @PathVariable bookmarkId: UUID) =
        service.deleteBookmark(principal.name, bookId, bookmarkId)
}
