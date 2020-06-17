/*
 * Module: r2-streamer-kotlin
 * Developers: Quentin Gliosca
 *
 * Copyright (c) 2020. Readium Foundation. All rights reserved.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.streamer

import android.app.Dialog
import android.content.Context
import org.readium.r2.shared.extensions.addPrefix
import org.readium.r2.shared.fetcher.ArchiveFetcher
import org.readium.r2.shared.fetcher.Fetcher
import org.readium.r2.shared.fetcher.FileFetcher
import org.readium.r2.shared.format.File
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.archive.Archive
import org.readium.r2.shared.util.archive.JavaZip
import org.readium.r2.shared.util.logging.WarningLogger
import org.readium.r2.shared.util.pdf.PdfDocument
import org.readium.r2.streamer.parser.pdf.PdfiumDocument

typealias OnAskCredentialsCallback = (dialog: Dialog, sender: Any?, callback: (String?) -> Unit) -> Unit

typealias StreamerTry<SuccessT> = Try<SuccessT, Streamer.Error>

/**
 *  Opens a Publication using a list of parsers.
 *
 *  @param parsers Parsers used to open a publication, in addition to the default parsers.
 *    The provided parsers take precedence over the default parsers.
 *    This can be used to provide custom parsers, or a different configuration for default parsers.
 *  @param ignoreDefaultParsers When true, only parsers provided in parsers will be used.
 *    Can be used if you want to support only a subset of Readium's parsers.
 *  @param openArchive Opens an archive (e.g. ZIP, RAR), optionally protected by credentials.
 *    The default implementation uses java.zip package and passwords are not supported with it.
 *  @param openPdf Parses a PDF document, optionally protected by password.
 *    The default implementation uses Pdfium.
 *  @param onCreateManifest Called before creating the Publication, to modify the parsed Manifest if desired.
 *  @param onCreateFetcher Called before creating the Publication, to modify its root fetcher.
 *  @param onCreateServices Called before creating the Publication, to modify its list of service factories.
 *  @param onAskCredentials Called when a content protection wants to prompt the user for its credentials.
 *    This is used by ZIP and PDF password protections.
 *    The default implementation of this callback presents a dialog using native components when possible.
 */
class Streamer(
    context: Context,
    parsers: List<PublicationParser> = emptyList(),
    ignoreDefaultParsers: Boolean = false,
    val contentProtections: List<ContentProtection> = emptyList(),
    val openArchive: (String) -> Archive? = (JavaZip)::open,
    val openPdf: (String) -> PdfDocument? = { PdfiumDocument.fromPath(it, context.applicationContext) },
    val onCreateManifest: (File, Manifest) -> Manifest = { _, manifest -> manifest },
    val onCreateFetcher: (File, Manifest, Fetcher) -> Fetcher = { _, _, fetcher -> fetcher },
    val onCreateServices: (File, Manifest, Publication.ServicesBuilder) -> Unit = { _, _, _ -> Unit },
    val onAskCredentials: OnAskCredentialsCallback = { _, _, _ -> Unit }
) {

    private val defaultParsers: List<PublicationParser> = listOf()
    private val parsers: List<PublicationParser> = parsers +
        if (ignoreDefaultParsers) defaultParsers else emptyList()

    /**
     * Parses a Publication from the given file.
     *
     * Returns null if the file was not recognized by any parser, or a Streamer.Error in case of failure.
     *
     * @param file Path to the publication file..
     * @param fallbackTitle The Publication's title is mandatory, but some formats might not have a way of declaring a title (e.g. CBZ).
     *   In which case, fallbackTitle will be used.
     *   The default implementation uses the filename as the fallback title.
     * @param askCredentials Indicates whether the user can be prompted for its credentials.
     *   This should be set to true when you want to render a publication in a Navigator.
     *   When false, Content Protections are allowed to do background requests, but not to present a UI to the user.
     * @param credentials Credentials that Content Protections can use to attempt to unlock a publication, for example a password.
     *   Supporting string credentials is entirely optional for Content Protections, this is only provided as a convenience.
     *   The format is free-form: it can be a simple password, or a structured format such as JSON or XML.
     * @param sender
     *   Free object that can be used by reading apps to give some context to the Content Protections.
     *   For example, it could be the source Activity/ViewController which would be used to present a credentials dialog.
     *   Content Protections are not supposed to use this parameter directly.
     *   Instead, it should be forwarded to the reading app if an interaction is needed.
     * @param warnings Logger used to broadcast non-fatal parsing warnings.
     *   Can be used to report publication authoring mistakes,
     *   to warn users of potential rendering issues or help authors debug their publications.
     */
    suspend fun open(
        file: File,
        fallbackTitle: String = file.name,
        askCredentials: Boolean,
        credentials: String? = null,
        sender: Any? = null,
        warnings: WarningLogger? = null
    ): StreamerTry<Publication>? {

        val leafFetcher = ArchiveFetcher.fromPath(file.path, openArchive)
            ?: run {
                val suffix = file.format()?.fileExtension?.addPrefix(".").orEmpty()
                FileFetcher("/publication${suffix}", file.file)
            }

        return try {
            val protectedFile = contentProtections
                .lazyMapFirstNotNullOrNull { it.open(file, leafFetcher, askCredentials, credentials, sender, onAskCredentials) }
                ?.getOrThrow()

            val builder = parsers
                .lazyMapFirstNotNullOrNull { it.parse(protectedFile?.file ?: file, leafFetcher, fallbackTitle, warnings) }
                ?.getOrThrow()
                ?: return null // no parser is able to parse publication

            val manifest = onCreateManifest(file, builder.manifest)
            val fetcher = onCreateFetcher(file, manifest, builder.fetcher)
            onCreateServices(file, manifest, builder.servicesBuilder)

            val publication = Publication(manifest, fetcher, servicesBuilder = builder.servicesBuilder)
            Try.success(publication)

        } catch (e: Error) {
            Try.failure(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T, R> List<T>.lazyMapFirstNotNullOrNull(transform: suspend (T) -> R): R? {
        for (it in this) {
            return transform(it) ?: continue
        }
        return null
    }

    /**
     * Errors occurring while opening a Publication.
     */
    sealed class Error(cause: Throwable? = null) : Throwable(cause) {

        class ParsingFailed(cause: Throwable) : Error(cause)

        /**
         * Returned when we're not allowed to open the Publication at all, for example because it expired.
         *
         * The Content Protection can provide a custom underlying error as an associated value.
         */
        class Forbidden(cause: Throwable?) : Error(cause)

        /**
         * Returned when the Content Protection can't open the Publication at the moment, for example because of a networking error.
         *
         * This error is generally temporary, so the operation can be retried or postponed.
         */
        class Unavailable(cause: Throwable?) : Error(cause)

        /**
         * Returned when the credentials – either from the credentials parameter, or from an external source – are incorrect,
         * and we can't create a locked Publication object, e.g. for a password-protected ZIP.
         */
        object IncorrectCredentials: Error()

    }
}
