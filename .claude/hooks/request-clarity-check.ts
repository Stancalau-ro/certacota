#!/usr/bin/env node
import { readFileSync } from 'fs';

interface HookInput {
    session_id: string;
    transcript_path: string;
    cwd: string;
    prompt: string;
}

interface VaguePattern {
    pattern: RegExp;
    category: string;
    questions: string[];
    minLength?: number;
}

const VAGUE_PATTERNS: VaguePattern[] = [
    {
        pattern: /^(fix|debug|solve)\s+(the\s+)?(bug|issue|problem|error)s?\s*(in|with|on)?\s*(\w+)?$/i,
        category: 'Bug Report',
        questions: [
            'What specific behavior are you seeing?',
            'What behavior did you expect instead?',
            'Can you describe how to reproduce it?'
        ]
    },
    {
        pattern: /^add\s+(a\s+)?(\w+)\s*(feature|functionality)?$/i,
        category: 'Feature Request',
        questions: [
            'Where in the application should this be added?',
            'What should trigger this feature?',
            'What data or inputs are involved?'
        ]
    },
    {
        pattern: /^(implement|create|build|make)\s+(a\s+)?(\w+)$/i,
        category: 'Implementation Request',
        questions: [
            'What specific behavior should this have?',
            'Are there existing patterns to follow?',
            'What are the acceptance criteria?'
        ],
        minLength: 0
    },
    {
        pattern: /^(improve|optimize|enhance|make\s+.*\s+better)\s+/i,
        category: 'Improvement Request',
        questions: [
            'What metric defines "better" (speed, readability, maintainability)?',
            'What is the current baseline?',
            'What is the target goal?'
        ]
    },
    {
        pattern: /^(clean\s*up|refactor|reorganize)\s+/i,
        category: 'Refactoring Request',
        questions: [
            'What specifically should be improved?',
            'What patterns should be followed?',
            'What is the scope boundary?'
        ]
    },
    {
        pattern: /^(change|update|modify)\s+(the\s+)?(\w+)$/i,
        category: 'Modification Request',
        questions: [
            'What should the new behavior be?',
            'What is the current behavior?',
            'Are there constraints to consider?'
        ]
    },
    {
        pattern: /^(setup|configure|set\s*up)\s+(\w+)$/i,
        category: 'Configuration Request',
        questions: [
            'What specific configuration is needed?',
            'Are there environment-specific requirements?',
            'What integration points are involved?'
        ]
    }
];

const EXCLUSION_PATTERNS = [
    /\?$/,
    /^(what|how|why|where|when|who|which|can you|could you|would you)/i,
    /^(show|list|find|search|look|check|read|get|fetch)/i,
    /^(explain|describe|tell me|help me understand)/i,
    /^(yes|no|ok|okay|sure|thanks|thank you)/i,
    /^(commit|push|pull|merge|branch|checkout)/i,
    /^(run|execute|start|stop|restart|test)/i,
    /in\s+[\w\/\\.]+\.(java|ts|js|py|go|rs|tsx|jsx|html|css|json|yaml|yml|xml|md)/i,
    /`[^`]+`/,
    /"[^"]+"/,
    /'[^']+'/
];

const CONTEXT_INDICATORS = [
    /following\s+(the\s+)?pattern/i,
    /like\s+(the\s+)?(other|existing)/i,
    /similar\s+to/i,
    /based\s+on/i,
    /using\s+(the\s+)?(\w+)\s+(approach|pattern|method)/i,
    /as\s+discussed/i,
    /from\s+the\s+/i,
    /in\s+the\s+\w+\s+(file|class|module|component|service)/i,
    /should\s+(be|have|do|work|return|accept|take)/i,
    /must\s+(be|have|do|work|return|accept|take)/i,
    /needs?\s+to\s+(be|have|do|work|return|accept|take)/i
];

function hasContext(prompt: string): boolean {
    return CONTEXT_INDICATORS.some(pattern => pattern.test(prompt));
}

function shouldExclude(prompt: string): boolean {
    return EXCLUSION_PATTERNS.some(pattern => pattern.test(prompt));
}

function isShortAndVague(prompt: string): boolean {
    const words = prompt.trim().split(/\s+/);
    return words.length <= 5 && !hasContext(prompt);
}

function analyzePrompt(prompt: string): { isVague: boolean; category?: string; questions?: string[] } {
    const trimmedPrompt = prompt.trim();

    if (shouldExclude(trimmedPrompt)) {
        return { isVague: false };
    }

    if (hasContext(trimmedPrompt) && trimmedPrompt.length > 50) {
        return { isVague: false };
    }

    for (const pattern of VAGUE_PATTERNS) {
        if (pattern.pattern.test(trimmedPrompt)) {
            const minLen = pattern.minLength ?? 30;
            if (trimmedPrompt.length < minLen || isShortAndVague(trimmedPrompt)) {
                return {
                    isVague: true,
                    category: pattern.category,
                    questions: pattern.questions
                };
            }
        }
    }

    const words = trimmedPrompt.split(/\s+/);
    if (words.length <= 3 && !shouldExclude(trimmedPrompt)) {
        const actionWords = ['add', 'fix', 'create', 'make', 'build', 'implement', 'change', 'update'];
        const hasAction = actionWords.some(word =>
            trimmedPrompt.toLowerCase().includes(word)
        );

        if (hasAction) {
            return {
                isVague: true,
                category: 'General Request',
                questions: [
                    'What specific outcome do you want?',
                    'Where should this be implemented?',
                    'Are there any constraints or requirements?'
                ]
            };
        }
    }

    return { isVague: false };
}

async function main() {
    try {
        const input = readFileSync(0, 'utf-8');
        const data: HookInput = JSON.parse(input);
        const prompt = data.prompt;

        const analysis = analyzePrompt(prompt);

        if (analysis.isVague && analysis.questions) {
            let output = '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n';
            output += '🔍 REQUEST CLARITY CHECK\n';
            output += '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n';
            output += `📋 Detected: ${analysis.category}\n\n`;
            output += '⚠️  This request may need clarification.\n\n';
            output += '💡 SUGGESTED QUESTIONS to ask the user:\n';
            analysis.questions.forEach((q, i) => {
                output += `   ${i + 1}. ${q}\n`;
            });
            output += '\n';
            output += '📌 ACTION: Consider using AskUserQuestion tool\n';
            output += '   OR use request-analyzer agent for complex cases\n';
            output += '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n';

            console.log(output);
        }

        process.exit(0);
    } catch (err) {
        process.exit(0);
    }
}

main().catch(() => process.exit(0));
