/*******************************************************************************
 * Copyright (c) 2016 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Angelo Zerr <angelo.zerr@gmail.com> - Bug 525400 - [rename] improve rename support with ltk UI
 *  Jan Koehnlein (TypeFox) - handle missing existing document gracefully
 *******************************************************************************/
package org.eclipse.lsp4e.operations.rename;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IHandler;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.eclipse.ltk.core.refactoring.participants.RefactoringProcessor;
import org.eclipse.ltk.ui.refactoring.RefactoringWizardOpenOperation;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.ITextEditor;

public class LSPRenameHandler extends AbstractHandler implements IHandler {

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IEditorPart part = HandlerUtil.getActiveEditor(event);
		if (!(part instanceof ITextEditor)) {
			return null;
		}
		ISelection sel = ((ITextEditor) part).getSelectionProvider().getSelection();
		if (!(sel instanceof ITextSelection) || sel.isEmpty()) {
			return null;
		}
		ITextSelection textSelection = (ITextSelection) sel;
		IDocument document = LSPEclipseUtils.getDocument((ITextEditor) part);
		if (document == null) {
			return null;
		}
		Shell shell = part.getSite().getShell();
		return LanguageServiceAccessor.getLanguageServers(document, LSPRenameHandler::isRenameProvider)
				.thenAcceptAsync(languageServers -> {
					if (languageServers.isEmpty()) {
						return;
					}
					int offset = textSelection.getOffset();
					// TODO consider better strategy to pick LS, or iterate over LS until one gives
					// a good result
					RefactoringProcessor processor = new LSPRenameProcessor(document, languageServers.get(0), offset);
					ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
					LSPRenameRefactoringWizard wizard = new LSPRenameRefactoringWizard(refactoring);
					RefactoringWizardOpenOperation operation = new RefactoringWizardOpenOperation(wizard);
					shell.getDisplay().asyncExec(() -> {
						try {
							operation.run(shell, Messages.rename_title);
						} catch (InterruptedException e1) {
							LanguageServerPlugin.logError(e1);
							Thread.currentThread().interrupt();
						}
					});
				});
	}

	public static boolean isRenameProvider(ServerCapabilities serverCapabilities) {
		if (serverCapabilities == null) {
			return false;
		}
		Either<Boolean, RenameOptions> renameProvider = serverCapabilities.getRenameProvider();
		if (renameProvider == null) {
			return false;
		}
		if (renameProvider.isLeft()) {
			return renameProvider.getLeft() != null && renameProvider.getLeft();
		}
		if (renameProvider.isRight()) {
			return renameProvider.getRight() != null;
		}
		return false;
	}

	@Override
	public boolean isEnabled() {
		IWorkbenchPart part = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getActivePart();
		ISelection selection = ((AbstractTextEditor) part).getSelectionProvider().getSelection();
		if (!(selection instanceof ITextSelection) || selection.isEmpty()) {
			return false;
		}
		if (!(part instanceof ITextEditor)) {
			return false;
		}
		IDocument document = LSPEclipseUtils.getDocument((ITextEditor) part);
		if (document == null) {
			return false;
		}
		try {
			return !LanguageServiceAccessor.getLanguageServers(document, LSPRenameHandler::isRenameProvider)
					.get(100, TimeUnit.MILLISECONDS).isEmpty();
		} catch (java.util.concurrent.ExecutionException | TimeoutException e) {
			LanguageServerPlugin.logError(e);
			return false;
		} catch (InterruptedException e) {
			LanguageServerPlugin.logError(e);
			Thread.currentThread().interrupt();
			return false;
		}
	}

}
