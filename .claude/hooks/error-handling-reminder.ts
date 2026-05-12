#!/usr/bin/env node
import { readFileSync, existsSync } from 'fs';
import { join } from 'path';
import { homedir } from 'os';

interface HookInput {
    session_id: string;
    transcript_path: string;
    cwd: string;
    permission_mode: string;
    hook_event_name: string;
}

interface EditedFile {
    path: string;
    tool: string;
    timestamp: string;
}

function getFileCategory(filePath: string): 'controller' | 'service' | 'repository' | 'config' | 'test' | 'other' {
    const normalizedPath = filePath.replace(/\\/g, '/');

    if (normalizedPath.includes('/controller/') || normalizedPath.includes('Controller.java')) return 'controller';
    if (normalizedPath.includes('/service/') || normalizedPath.includes('Service.java')) return 'service';
    if (normalizedPath.includes('/repository/') || normalizedPath.includes('Repository.java')) return 'repository';
    if (normalizedPath.includes('/config/') || normalizedPath.includes('Config.java')) return 'config';
    if (normalizedPath.includes('/test/') || normalizedPath.includes('Test.java')) return 'test';

    return 'other';
}

function shouldCheckErrorHandling(filePath: string): boolean {
    if (filePath.match(/Test\.java$/)) return false;
    if (filePath.match(/Tests\.java$/)) return false;
    if (filePath.includes('/test/')) return false;

    return filePath.match(/\.java$/) !== null;
}

function analyzeFileContent(filePath: string): {
    hasLogging: boolean;
    hasTransactional: boolean;
    hasExceptionHandler: boolean;
    hasController: boolean;
    hasService: boolean;
    hasRepository: boolean;
} {
    if (!existsSync(filePath)) {
        return { hasLogging: false, hasTransactional: false, hasExceptionHandler: false, hasController: false, hasService: false, hasRepository: false };
    }

    const content = readFileSync(filePath, 'utf-8');

    return {
        hasLogging: /@Slf4j|private.*Logger|LoggerFactory/.test(content),
        hasTransactional: /@Transactional/.test(content),
        hasExceptionHandler: /@ExceptionHandler|@ControllerAdvice/.test(content),
        hasController: /@RestController|@Controller/.test(content),
        hasService: /@Service/.test(content),
        hasRepository: /@Repository|JpaRepository|CrudRepository/.test(content),
    };
}

async function main() {
    try {
        // Read input from stdin
        const input = readFileSync(0, 'utf-8');
        const data: HookInput = JSON.parse(input);

        const { session_id } = data;
        const projectDir = process.env.CLAUDE_PROJECT_DIR || process.cwd();

        // Check for edited files tracking
        const cacheDir = join(homedir(), '.claude', 'tsc-cache', session_id);
        const trackingFile = join(cacheDir, 'edited-files.log');

        if (!existsSync(trackingFile)) {
            // No files edited this session, no reminder needed
            process.exit(0);
        }

        // Read tracking data
        const trackingContent = readFileSync(trackingFile, 'utf-8');
        const editedFiles = trackingContent
            .trim()
            .split('\n')
            .filter(line => line.length > 0)
            .map(line => {
                const [timestamp, tool, path] = line.split('\t');
                return { timestamp, tool, path };
            });

        if (editedFiles.length === 0) {
            process.exit(0);
        }

        // Categorize files
        const categories = {
            controller: [] as string[],
            service: [] as string[],
            repository: [] as string[],
            config: [] as string[],
            test: [] as string[],
            other: [] as string[],
        };

        const analysisResults: Array<{
            path: string;
            category: string;
            analysis: ReturnType<typeof analyzeFileContent>;
        }> = [];

        for (const file of editedFiles) {
            if (!shouldCheckErrorHandling(file.path)) continue;

            const category = getFileCategory(file.path);
            categories[category].push(file.path);

            const analysis = analyzeFileContent(file.path);
            analysisResults.push({ path: file.path, category, analysis });
        }

        // Check if any Spring Boot code was written
        const needsAttention = analysisResults.some(
            ({ analysis }) =>
                analysis.hasController ||
                analysis.hasService ||
                analysis.hasRepository
        );

        if (!needsAttention) {
            process.exit(0);
        }

        // Display reminder
        console.log('\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
        console.log('SPRING BOOT ERROR HANDLING CHECK');
        console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n');

        // Controller reminders
        if (categories.controller.length > 0) {
            const controllerFiles = analysisResults.filter(f => f.category === 'controller');
            const hasLogging = controllerFiles.some(f => f.analysis.hasLogging);
            const hasExceptionHandler = controllerFiles.some(f => f.analysis.hasExceptionHandler);

            console.log('Controller Changes Detected');
            console.log(`   ${categories.controller.length} file(s) edited\n`);

            if (!hasLogging) {
                console.log('   ? Consider adding @Slf4j for logging');
            }
            if (!hasExceptionHandler) {
                console.log('   ? Is there a @ControllerAdvice for exception handling?');
            }

            console.log('\n   Best Practices:');
            console.log('      - Use @Slf4j for logging');
            console.log('      - Return proper ResponseEntity with status codes');
            console.log('      - Use @Valid for request validation\n');
        }

        // Service reminders
        if (categories.service.length > 0) {
            const serviceFiles = analysisResults.filter(f => f.category === 'service');
            const hasTransactional = serviceFiles.some(f => f.analysis.hasTransactional);
            const hasLogging = serviceFiles.some(f => f.analysis.hasLogging);

            console.log('Service Changes Detected');
            console.log(`   ${categories.service.length} file(s) edited\n`);

            if (!hasTransactional) {
                console.log('   ? Consider @Transactional for database operations');
            }
            if (!hasLogging) {
                console.log('   ? Consider adding @Slf4j for logging');
            }

            console.log('\n   Best Practices:');
            console.log('      - Use @Transactional for write operations');
            console.log('      - Throw custom exceptions, not generic ones');
            console.log('      - Log important business events\n');
        }

        // Repository reminders
        if (categories.repository.length > 0) {
            console.log('Repository Changes Detected');
            console.log(`   ${categories.repository.length} file(s) edited\n`);
            console.log('   ? Verify query methods follow naming conventions');
            console.log('   ? Consider using @Query for complex queries\n');
        }

        console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━');
        console.log('TIP: Disable with SKIP_ERROR_REMINDER=1');
        console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n');

        process.exit(0);
    } catch (err) {
        // Silently fail - this is just a reminder, not critical
        process.exit(0);
    }
}

main().catch(() => process.exit(0));
